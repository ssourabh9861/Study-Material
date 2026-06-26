# Consistent Hashing

> **Concept area:** Database Scaling & Partitioning
> **Subtopic:** Consistent Hashing
> **Reader:** a senior Java/JVM backend developer who wants to master this from first principles to deep internals — enough to design with it, operate and debug it in production, teach it, and answer any interview question on it.

---

## Table of contents

1. [Overview & where it fits](#1-overview--where-it-fits)
2. [Foundations from first principles](#2-foundations-from-first-principles)
3. [How it works internally](#3-how-it-works-internally)
4. [The complete toolkit](#4-the-complete-toolkit)
5. [Code examples by use case](#5-code-examples-by-use-case)
6. [Implementation concerns & best practices](#6-implementation-concerns--best-practices)
7. [Advanced topics & deep internals](#7-advanced-topics--deep-internals)
8. [Tradeoffs & decision frameworks](#8-tradeoffs--decision-frameworks)
9. [Failure modes & debugging](#9-failure-modes--debugging)
10. [Interview drill](#10-interview-drill)
11. [Glossary](#11-glossary)
12. [Cheat-sheet & self-test](#12-cheat-sheet--self-test)

---

## 1. Overview & where it fits

**Consistent hashing** is a technique for distributing a set of *keys* (cache entries, database rows, partitions, sessions, files, requests) across a set of *nodes* (cache servers, database shards, storage nodes) such that **when the set of nodes changes — a node is added or removed — only a small fraction of keys need to move**, instead of nearly all of them.

The core problem it solves: in a distributed system you must decide *which node owns which key*. The naive answer is `node = hash(key) % N`, where `N` is the number of nodes. This works perfectly while `N` is fixed, but the instant `N` changes — you scale out, a server dies, you add capacity — the modulus changes and **almost every key maps to a different node**. In a cache, that means a near-total cache miss storm (a "cold cache") that can knock over your backing database. In a sharded database, it means a colossal data-reshuffle. Consistent hashing makes the disruption proportional to the *change* in capacity, not the *total* capacity: adding the *N*th node moves only about `1/N` of the keys.

**When you reach for it:**
- **Distributed caches** (Memcached client-side sharding, Redis client libraries, CDN edge caches) where you want cache hit rates to survive server churn.
- **Partitioned/sharded databases and key-value stores** — Amazon DynamoDB's internals, Apache Cassandra, Riak, ScyllaDB, Voldemort — where data must be spread across nodes and rebalanced cheaply.
- **Load balancers and proxies** that want *session affinity* / *sticky routing* (the same client or key consistently hits the same backend) that degrades gracefully as backends come and go — e.g. Envoy's `ring_hash` and `maglev` policies, HAProxy's consistent hashing, NGINX `hash ... consistent`.
- **Sharded stream/queue consumers**, distributed rate limiters, and any system that maps a key space onto a worker pool that resizes.

**One-paragraph mental model.** Imagine a clock face — a circle of positions numbered `0` to `2³²−1` (or `2⁶⁴−1`) that wraps around at the top. You hash each *node* to a point on this ring, and you hash each *key* to a point on the same ring. To find the node that owns a key, you start at the key's position and walk clockwise until you hit the first node; that node owns the key. Because each node owns the arc *behind* it (counter-clockwise) up to the previous node, removing a node only hands its arc to its clockwise neighbor — every other arc is untouched. Adding a node only carves a slice out of one existing node's arc. To make the arcs evenly sized despite random hashing, you place many *virtual copies* of each node around the ring. That is the whole idea; everything else is refinement (balancing, bounded load, alternatives like rendezvous hashing).

---

## 2. Foundations from first principles

### 2.1 The vocabulary, defined as we go

- **Key.** The identifier you are routing on — e.g. a cache key string `"user:42:profile"`, a database primary key, a partition key, a user ID, a URL. We will hash keys to decide placement.
- **Node.** A physical or logical destination that stores or serves keys — a cache server, a database shard, a backend instance. Also called a *bucket*, *shard*, *server*, or *member* depending on the system.
- **Hash function.** A deterministic function `h(x)` that maps an arbitrary input `x` to a fixed-size integer, ideally spreading inputs *uniformly* and *unpredictably* across the output range. "Deterministic" = same input always gives same output. "Uniform" = outputs are spread evenly, so two unrelated inputs are unlikely to collide and the distribution has no clumps. Examples: MD5, SHA-1, MurmurHash3, xxHash, FNV. (We are using hashing for *distribution*, not for *security*, so a fast non-cryptographic hash like MurmurHash3 is usually preferred — see §6.)
- **Hash space / key space.** The range of possible outputs of the hash function, e.g. `0 .. 2³²−1` for a 32-bit hash. We will conceptually bend this range into a circle.
- **Partition / shard.** A subset of the key space assigned to one node. In consistent hashing, a partition is an *arc* of the ring.
- **Rebalancing.** The act of moving keys between nodes after the node set changes. The whole point of consistent hashing is to minimize rebalancing.

### 2.2 The naive baseline: modulo-N hashing (and why it fails)

The simplest sharding scheme:

```
nodeIndex = hash(key) % N      // N = number of nodes
```

Suppose `N = 4` nodes (indices 0..3) and a key whose hash is `1234567`:

```
1234567 % 4 = 3   → node 3
```

This is great: O(1), trivially uniform if `hash` is uniform, no data structures. **The catastrophe is what happens on resize.** Add one node (`N` goes 4 → 5):

```
1234567 % 4 = 3   (old owner)
1234567 % 5 = 2   (new owner)   → key moved
```

Let us quantify the damage. With modulo, a key stays put across the resize `N → N+1` only if `hash(key) % N == hash(key) % (N+1)`. For hashes uniform over a large range, the **expected fraction of keys that keep the same owner** when going from `N` to `N+1` buckets is roughly `1/(N+1)` — meaning **about `N/(N+1)` of all keys move**. Concretely:

| Resize | Approx. fraction of keys that move |
|---|---|
| 1 → 2 | ~50% |
| 4 → 5 | ~80% |
| 9 → 10 | ~90% |
| 99 → 100 | ~99% |

So scaling a 99-node cluster to 100 nodes with modulo hashing remaps ~99% of all keys. For a cache, that is a near-total cold-cache event: every remapped key misses, the misses stampede the database, and you can take down the whole system *by adding capacity*. This phenomenon — many clients simultaneously missing and hammering the backing store — is called a **cache stampede** or **thundering herd**. Modulo hashing turns a routine scaling operation into an outage trigger.

**Why does modulo behave this way?** Because `% N` ties *every* key's destination to the *exact value of N*. There is no locality: changing the divisor reshuffles the entire mapping. We want a scheme where a node's identity, not the global count, determines ownership, so a change to one node leaves the others' assignments intact.

### 2.3 Consistent hashing derived from scratch

Karger et al. introduced consistent hashing in 1997 (the paper "Consistent Hashing and Random Trees," in the context of distributed web caching for what became Akamai). Here is the derivation.

**Step 1 — Put keys and nodes in the same space.** Choose a hash function with output range `[0, M)` where `M = 2^b` (commonly `b = 32`, sometimes `b = 64` or `128`). Hash *keys* into this range, and **also hash nodes** into the same range (hash the node's identity — its IP, hostname, name, or `host:port`).

**Step 2 — Bend the range into a ring.** Treat the range as circular: position `M−1` is adjacent to position `0`. This is the **hash ring** (a.k.a. *consistent hash ring* or *token ring*). Arithmetic is modulo `M`, so "walk clockwise past the top" wraps to `0`.

**Step 3 — Define ownership by clockwise successor.** A key `k` at position `h(k)` is owned by the **first node encountered when walking clockwise from `h(k)`** — i.e. the node with the *smallest position ≥ h(k)* (wrapping around if none). Equivalently, each node owns the arc from the previous node (exclusive) up to and including itself.

```
Ring (positions increase clockwise), 4 nodes A,B,C,D at random positions:

            0/M
             |
      D •----+----• A
       /            \
      |   k1 → A     |   (k1 sits just before A, walks cw to A)
      |              |
      • C          • B
       \            /
        +----------+
            key k2 between B and C → owned by C
```

**Step 4 — Observe the magic on membership change.**

- **Remove node B.** Only the arc that *was* B's (from A's position to B's position) is affected; those keys now walk clockwise to the next node after B (say C). **No other node's arc changes.** Roughly `1/N` of keys move, and they all move to *one* neighbor.
- **Add node E** at some position. E carves out the portion of an existing arc that lies between the previous node and E. Only keys in that slice move (from the old owner to E). Again ~`1/N` of keys, taken from *one* node.

This is the defining property: **a membership change of one node disturbs only its immediate ring neighborhood, moving an expected `K/N` keys (where K = total keys, N = nodes), rather than `K·(N−1)/N`.**

**Step 5 — The balance problem.** With only one point per node, the arcs are random and therefore *uneven*. Hashing `N` nodes to random positions produces arc lengths that follow roughly an exponential distribution; the largest arc can be several times the average. With `N` nodes, the expected ratio of the largest to the smallest load is large, and the standard deviation of load is high. Empirically, with one point per node, load can vary by a factor of ~5x or more across nodes for small `N`. That is unacceptable for capacity planning. The fix is **virtual nodes**.

### 2.4 Virtual nodes (vnodes / replicas) — why they're needed

Instead of placing each physical node at **one** point, place it at **V** points by hashing `V` distinct labels per node, e.g. `h("nodeA#0"), h("nodeA#1"), ..., h("nodeA#(V-1)")`. Each of these is a **virtual node** (also called a *vnode*, *replica*, or *token* — note "replica" here means *ring point*, **not** a data replica; this naming collision trips people up). A key is owned by the physical node behind the virtual node it walks clockwise into.

Why this helps:

1. **Smoothing / balance.** With `V` points per node and `N` nodes, the ring has `N·V` points. The arcs each node owns are now the sum of `V` random arcs, and by the law of large numbers their total tends toward the mean. The **coefficient of variation of load shrinks roughly as `1/√(N·V)`**. With `V` in the range of 100–500 per node, load typically lands within a few percent of even. (Rule of thumb: ~`1/√V` relative standard deviation per node from vnodes alone.)

2. **Smoother rebalancing.** When a node leaves, its `V` arcs scatter to `V` different clockwise neighbors rather than dumping its entire load on one neighbor. So both the *source distribution* and the *destination distribution* of moved keys are even — no single neighbor gets slammed.

3. **Heterogeneous capacity (weighting).** A node twice as powerful gets twice as many virtual nodes (`2V`), so it owns ~2x the key space. This is how you mix machine sizes in one cluster. Weight = (vnode count) ∝ (capacity).

**Cost of virtual nodes:** more ring entries → more memory for the ring structure, and lookups over a larger sorted structure (still `O(log(N·V))`). Also, more vnodes = more *neighbors* per node, which matters for replication topology and for the blast radius of correlated failures (see §7). Typical production values: Cassandra historically defaulted to `num_tokens = 256`, later guidance moved to **16** (with newer token-allocation algorithms) because 256 hurt streaming/repair and increased the chance that any single failure touches every node. Memcached client libraries (Ketama) use ~**160 points per server** (40 "continuum" entries × 4, from MD5 producing 4 ring points per hash). Dynamo-style systems and Envoy `ring_hash` let you configure ring size directly (Envoy default `minimum_ring_size = 1024`, `maximum_ring_size = 8M`).

### 2.5 The fraction of keys moved — the precise statement

- **Adding the Nth node** (going from `N−1` to `N` nodes): expected fraction of keys moved ≈ `1/N`. They are taken evenly from existing nodes when virtual nodes are used; without vnodes they come from one node.
- **Removing one of `N` nodes:** expected fraction moved ≈ `1/N`, redistributed to neighbors (evenly with vnodes).
- **Contrast with modulo:** modulo moves ≈ `(N−1)/N` on the same operations.

This `1/N` "minimal disruption" is in fact close to *optimal*: you cannot move fewer than `1/N` of keys when changing capacity by one node out of `N` if you want even balance afterward, because the new node must end up owning ~`1/N` of the keys, and those keys must have come from somewhere.

---

## 3. How it works internally

This is the heart of the document. We trace the data structures, the lookup algorithm, the lifecycle of membership changes, and the state transitions, step by step.

### 3.1 The core data structure

A consistent-hash ring is almost always implemented as a **sorted map from ring position → node** (the positions being virtual-node hashes). In Java the canonical choice is `java.util.TreeMap<Long, Node>`, a red-black tree giving sorted order and `O(log n)` floor/ceiling queries.

```
TreeMap<Long, String> ring;   // position -> physicalNodeId

// Conceptual contents after adding nodes A,B with V=3 each:
//   1500000000  -> A     (hash of "A#0")
//   1899999000  -> B     (hash of "B#2")
//   2300000000  -> A     (hash of "A#1")
//   2700000000  -> B     (hash of "B#0")
//   3100000000  -> A     (hash of "A#2")
//   3500000000  -> B     (hash of "B#1")
```

The **ceiling/successor** operation is what implements "walk clockwise": for a key hash `hk`, find the smallest ring position `≥ hk`; if none exists (the key is past the last point), wrap to the first point.

### 3.2 Lookup workflow (control + data flow), step by step

Goal: given a key, find its owning node.

1. **Hash the key.** `hk = hash(keyBytes)` → a value in `[0, M)`. (Pick the *same* hash family used to place vnodes; mixing families is fine if both map into the same numeric range, but keep it consistent for clarity.)
2. **Find the clockwise successor.** Query the sorted ring for `ceilingEntry(hk)` (smallest position ≥ `hk`).
3. **Wrap-around handling.** If `ceilingEntry` returns `null` (no position ≥ `hk`, i.e. the key is past the last vnode), take `firstEntry()` — the smallest position — to wrap past the top of the ring.
4. **Resolve virtual → physical.** The map value is the *physical* node id for that vnode. Return it.
5. **(If replicating)** continue walking clockwise to collect the next `R−1` *distinct physical* nodes for replicas (see §3.5).

Complexity: `O(1)` hash + `O(log(N·V))` tree lookup. With `N·V = 100k` points, `log₂ ≈ 17` comparisons — sub-microsecond. Memory: `O(N·V)` ring entries (each ~tens of bytes in a `TreeMap` due to node overhead; see §6 for compact alternatives).

### 3.3 Adding a node — lifecycle

State transition: ring goes from membership set `S` to `S ∪ {X}`.

1. **Generate vnode positions for X.** For `i in 0..V-1`, compute `p_i = hash(label(X, i))` where `label` is a deterministic function like `X + "#" + i` (or `X + "-" + i`, or an HMAC — just be consistent forever, since changing the labeling reshuffles the ring).
2. **Insert into the ring.** For each `p_i`, `ring.put(p_i, X)`. Handle collisions (two vnodes hashing to the same position) deterministically — e.g. probe `p_i + 1`, or keep a list, or accept that one overwrites the other (rare with 64-bit space). Be consistent across all participants.
3. **Determine moved key ranges.** Each new vnode `p_i` lands inside an arc previously owned by some node `Y` (the clockwise successor of `p_i` before insertion). The keys in `(predecessor(p_i), p_i]` now belong to `X` instead of `Y`.
4. **Migrate data (stateful systems only).** For each affected arc, stream the relevant keys from `Y` to `X`. In a cache you usually *don't* migrate — you just let the new node start cold and let the old node's now-orphaned entries expire (TTL). In a database you must copy/stream the data (Cassandra "bootstrap"/streaming, Dynamo "hinted handoff" + repair).
5. **Cutover.** Once `X` has the data (or immediately, for caches), routing tables are updated so lookups see `X`. With vnodes, ~`1/N` of keys moved, sourced evenly from all existing nodes.

### 3.4 Removing a node — lifecycle

State transition: `S` → `S \ {X}` (planned decommission) or `X` becomes unreachable (failure).

1. **Identify X's vnode positions** (`p_0..p_{V-1}`).
2. **Reassign arcs.** Each `p_i`'s keys go to the *next distinct physical node clockwise* from `p_i`. With vnodes these destinations are spread across many nodes.
3. **Migrate / recover data.**
   - *Planned removal (decommission):* stream X's data to the new owners first, then remove X's points from the ring.
   - *Crash (failure):* the data on X is gone; the new owners must reconstruct it from **replicas** (see §3.5) — that is why stateful systems replicate. For caches, the keys simply become misses against the (now correct) new owner and repopulate from the backing store.
4. **Remove points from ring:** `ring.remove(p_i)` for each.

### 3.5 Replication on the ring (how Dynamo/Cassandra place copies)

Consistent hashing also drives **replica placement**, not just primary ownership. To store `R` copies of a key (the **replication factor**, RF):

1. Find the primary owner by walking clockwise (the **coordinator** / first replica).
2. Continue walking clockwise, collecting the next `R−1` **distinct physical nodes** (skip additional vnodes that map to the same physical node, and — in rack/datacenter-aware strategies — skip nodes in the same failure domain to spread replicas across racks/AZs). These `R` nodes form the **preference list** (Dynamo's term) or **replica set**.

Terms to define:
- **Replication factor (RF / R):** how many copies of each key exist. RF=3 is the common default (survives 2 simultaneous node losses for that key).
- **Coordinator:** the node that receives the client request and orchestrates the read/write across the replica set.
- **Preference list (Dynamo):** the ordered list of nodes responsible for a key (primary + the clockwise successors), skipping virtual duplicates and unhealthy nodes.
- **Quorum:** to keep replicas consistent, reads and writes touch a configurable number of replicas. With RF=N, write quorum `W` and read quorum `R_q`, you get strong consistency if `W + R_q > N` (overlapping read/write sets guarantee a read sees the latest write). Cassandra exposes this as consistency levels `ONE`, `QUORUM`, `ALL`, etc.

Why "distinct physical, distinct failure domain"? If two of the three replicas of a key live on the same machine (because two vnodes of the same physical node are adjacent) or in the same rack, a single failure can lose multiple copies. Replica-walk logic must therefore skip duplicates and respect topology.

### 3.6 State machine of a node's membership

```
        (operator adds)            (bootstrap/stream done)
 ABSENT ───────────────▶ JOINING ──────────────────────▶ NORMAL
   ▲                        │                               │
   │                        │ (failure during join)         │ (operator decommissions)
   │                        ▼                               ▼
   │                     FAILED ◀──── (node crash) ──── LEAVING
   │                        │                               │ (stream out done)
   │  (operator removes)    │                               ▼
   └────────────────────────┴──────────────────────────▶ REMOVED → ABSENT
```

- **JOINING:** vnodes are reserved; data is being streamed in; the node may serve reads from old owners until cutover.
- **NORMAL:** fully owns its arcs, serves reads/writes.
- **LEAVING:** streaming data out to new owners (decommission).
- **FAILED:** crashed/unreachable; replicas serve in its place; hinted handoff buffers writes for it.
- These mirror Cassandra's gossip states (`JOINING`, `NORMAL`, `LEAVING`, `MOVING`, `DOWN`). Gossip is the peer-to-peer protocol nodes use to exchange membership and state every ~1s (see §7).

### 3.7 Bounded-load consistent hashing (overview here, detail in §7)

Plain consistent hashing balances *on average* but a single hot key (or a skewed key distribution) can overload one node. **Consistent Hashing with Bounded Loads (CHBL)** (Mirrokni, Thorup, Zadimoghaddam, Google, 2016) adds a cap: each node may hold at most `(1 + ε)` times the average load. When a key's natural owner is at capacity, the key "spills" to the next clockwise node that has room. This bounds the worst-case load while still moving only `O(1/ε²)` extra keys per membership change. Used in Google Cloud Pub/Sub and available in Vimeo's `consistent` Go library and in Envoy-adjacent designs. Detail and code in §7.

### 3.8 Rendezvous (HRW) hashing — the ring-free alternative (overview)

**Rendezvous hashing**, a.k.a. **Highest Random Weight (HRW)** hashing (Thaler & Ravishankar, 1996 — actually predates Karger's ring paper), achieves the same minimal-disruption goal *without a ring*. For a key `k`, compute `w_i = hash(k, node_i)` for **every** node, and pick the node with the **highest** `w_i`. Properties: perfect balance in expectation, ~`1/N` keys move on membership change, trivial weighting, and you can get the *top-R* nodes for replication by taking the `R` highest scores — automatically distinct, automatically ordered. The cost is `O(N)` per lookup (vs `O(log(N·V))` for a ring), which is fine for small/medium `N` and is why HRW is popular in CDNs and client libraries with modest node counts. Detail and code in §7.

---

## 4. The complete toolkit

This section enumerates the building blocks: hash functions, the Java data structures and APIs, the configuration knobs across real systems, and the relevant CLI/operational commands.

### 4.1 Hash functions to place keys/nodes

| Hash | Output bits | Speed | Use for consistent hashing? | Notes |
|---|---|---|---|---|
| **MurmurHash3** | 32 or 128 | Very fast | **Yes (preferred)** | Excellent distribution, non-cryptographic. Cassandra uses Murmur3 (`Murmur3Partitioner`) as its default partitioner. Guava: `Hashing.murmur3_128()`. |
| **xxHash / xxh3** | 32/64/128 | Fastest | Yes | Great distribution and throughput; common in newer systems. |
| **FNV-1a** | 32/64 | Fast | Acceptable | Simple, decent distribution; weaker than Murmur on some patterns. |
| **CityHash / FarmHash** | 64/128 | Very fast | Yes | Google hashes; good distribution. |
| **MD5** | 128 | Slow-ish | Yes (legacy) | Used by **Ketama** (memcached). Cryptographically broken but fine for distribution; chosen historically for uniformity. Each MD5 yields 4 ring points (16 bytes → 4× 4-byte ints). |
| **SHA-1** | 160 | Slow | Possible | Overkill; only if you already depend on it. |
| **CRC32** | 32 | Fast | **Avoid** | Poor avalanche; clustering risk. Fine as a checksum, not for distribution. |
| **String.hashCode()** | 32 | Fast | **Avoid** | Weak distribution, easy collisions/clumping. Never use for ring placement. |

**Key takeaway:** prefer a fast, well-distributed *non-cryptographic* hash (MurmurHash3 / xxHash). Use a cryptographic hash only if you must defend against an adversary crafting keys to overload a node (algorithmic-complexity / hash-flooding attack — see §6 Security). Avoid `String.hashCode()` and CRC32 for placement.

### 4.2 Java data structures & APIs for the ring

| API / class | Purpose | Key methods / params | Defaults / notes |
|---|---|---|---|
| `java.util.TreeMap<Long,V>` | Sorted ring of position→node | `put`, `remove`, `ceilingEntry(k)`, `firstEntry()`, `tailMap(k,true)` | Red-black tree, `O(log n)` ops. The standard choice. |
| `java.util.NavigableMap` | Interface implemented by TreeMap | `ceilingKey`, `floorKey`, `higherKey` | Use `ceiling*` for clockwise walk. |
| `long[]` sorted array + `Arrays.binarySearch` | Compact, cache-friendly ring | binary search → insertion point | Lowest memory & best CPU-cache locality, but `O(N·V)` rebuild on membership change. Great for *read-mostly* rings (rebuild rarely). |
| `com.google.common.hash.Hashing.consistentHash(long, int)` | Guava's **Jump consistent hash** | `consistentHash(hashOrHashCode, buckets)` | NOT ring-based — see §7.5. Maps a hash to `[0, buckets)`; minimal movement when `buckets` grows. Cannot remove arbitrary buckets, only the highest. |
| `Hashing.murmur3_128()` / `murmur3_32_fixed()` | Hash functions | `hashString`, `hashBytes`, `hashLong` | Use for key/node hashing. |
| `MessageDigest.getInstance("MD5")` | Ketama-style hashing | `digest(bytes)` | For compatibility with memcached Ketama clients. |

### 4.3 Configuration knobs in real systems

| System | Knob | What it controls | Default / typical |
|---|---|---|---|
| **Apache Cassandra** | `num_tokens` (cassandra.yaml) | Virtual nodes per physical node | Historically **256**; modern guidance **16** (with `allocate_tokens_for_keyspace`/`allocate_tokens_for_local_replication_factor` for even allocation). |
| Cassandra | `partitioner` | Hash function for token ring | `Murmur3Partitioner` (default; 64-bit signed token space). Legacy: `RandomPartitioner` (MD5). |
| Cassandra | `endpoint_snitch` + replication strategy | Topology-aware replica placement | `NetworkTopologyStrategy` spreads replicas across racks/DCs. |
| Cassandra | `initial_token` | Manual token assignment | Empty (auto). |
| **Envoy proxy** | `ring_hash_lb_config.minimum_ring_size` | Min ring points total | **1024** |
| Envoy | `ring_hash_lb_config.maximum_ring_size` | Max ring points | **8,388,608 (8M)** |
| Envoy | `hash_balance_factor` (for `maglev`/bounded) | Bounded-load factor `(1+ε)` | e.g. 125 = `1.25×` mean cap |
| Envoy | LB policy | `RING_HASH` vs `MAGLEV` | Maglev: fixed-size table (default table size **65537**), faster lookups, slightly worse disruption than ring. |
| **NGINX** | `hash $key consistent;` | Enable Ketama-style ring on upstream | `consistent` keyword toggles ring vs plain modulo. |
| **HAProxy** | `hash-type consistent murmur3` | Ring + hash function | `hash-type map-based` (modulo) is default; `consistent` opts in. |
| **memcached (Ketama clients)** | points per server (`POINTS_PER_SERVER`) | Vnodes per server | spymemcached/libketama: **160** continuum points/server. |
| **Redis (client libs)** | depends on client | Some use Ketama; Redis *Cluster* itself uses 16384 hash slots (not a ring). | Redis Cluster: `CRC16(key) % 16384` slots, mapped to nodes (see §8). |
| **Riak** | `ring_creation_size` | Number of partitions (vnodes) | **64** (power of two); fixed at cluster creation. |
| **DynamoDB** | (internal, not user-tunable) | Amazon-managed partitioning | Uses consistent hashing internally; users see partition keys. |

### 4.4 Operational / CLI commands (Cassandra as the canonical example)

| Command | Purpose |
|---|---|
| `nodetool ring` | Print the token ring: every token, owner, and status. |
| `nodetool status` | Per-node UN/DN state, ownership %, load. Shows whether vnodes balance is healthy. |
| `nodetool describering <keyspace>` | Show token ranges and their replica endpoints. |
| `nodetool decommission` | Gracefully remove the *current* node, streaming its data to new owners (LEAVING state). |
| `nodetool removenode <hostid>` | Remove a *dead* node; survivors stream from replicas to restore RF. |
| `nodetool move <newtoken>` | Manually move a node to a new token (single-token mode). |
| `nodetool cleanup` | After topology change, delete keys a node no longer owns (reclaim disk). |
| `nodetool repair` | Reconcile replicas (anti-entropy) — vital after failures/joins to fix divergence. |
| `nodetool rebuild` | Stream data into a new node/DC. |
| `nodetool getendpoints <ks> <table> <key>` | Show which nodes own a *specific* key — directly demonstrates ring placement. |

For Envoy/NGINX/HAProxy the "commands" are config reloads and admin endpoints (e.g. Envoy's `/clusters` admin page shows ring membership and per-host load).

---

## 5. Code examples by use case

All Java unless noted. Each example targets a *different* scenario. Comments explain the non-obvious lines.

### 5.1 A minimal, correct consistent-hash ring with virtual nodes (the foundation)

```java
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Generic consistent-hash ring with virtual nodes and per-node weighting.
 * Use case: client-side sharding of cache keys across a set of servers.
 */
public final class ConsistentHashRing<T> {

    private final HashFunction hash = Hashing.murmur3_128(); // fast, well-distributed
    private final int vnodesPerUnitWeight;                   // base vnode count
    // The ring: ring position -> physical node. TreeMap gives O(log n) clockwise lookup.
    private final NavigableMap<Long, T> ring = new TreeMap<>();
    // Track each node's vnode positions so we can remove cleanly.
    private final Map<T, List<Long>> nodePositions = new HashMap<>();

    public ConsistentHashRing(int vnodesPerUnitWeight) {
        this.vnodesPerUnitWeight = vnodesPerUnitWeight;
    }

    /** Hash an arbitrary string label to a 64-bit ring position. */
    private long hash(String label) {
        // asLong() takes the lower 64 bits of the 128-bit murmur3 hash.
        return hash.hashString(label, StandardCharsets.UTF_8).asLong();
    }

    /** Add a node with a given weight (weight 2 => twice the vnodes => ~2x key share). */
    public synchronized void addNode(T node, int weight) {
        int vnodes = vnodesPerUnitWeight * Math.max(1, weight);
        List<Long> positions = new ArrayList<>(vnodes);
        for (int i = 0; i < vnodes; i++) {
            // Deterministic, stable label. NEVER change this format later or the
            // entire ring reshuffles. "#i" is the virtual-node index.
            long p = hash(node.toString() + "#" + i);
            // Linear-probe on the (rare) 64-bit collision so we don't lose a vnode.
            while (ring.containsKey(p)) p++;
            ring.put(p, node);
            positions.add(p);
        }
        nodePositions.put(node, positions);
    }

    public synchronized void removeNode(T node) {
        List<Long> positions = nodePositions.remove(node);
        if (positions != null) positions.forEach(ring::remove);
    }

    /** Find the node owning a key (clockwise successor, wrapping at the top). */
    public T getNode(String key) {
        if (ring.isEmpty()) return null;
        long h = hash(key);
        Map.Entry<Long, T> e = ring.ceilingEntry(h); // smallest position >= h
        if (e == null) e = ring.firstEntry();         // wrap around the ring
        return e.getValue();
    }

    /** Get the R distinct physical nodes for replication (primary + successors). */
    public List<T> getNodes(String key, int replicas) {
        if (ring.isEmpty()) return List.of();
        long h = hash(key);
        List<T> result = new ArrayList<>(replicas);
        // tailMap(h, true) gives all entries clockwise from h; then we wrap with the head.
        Iterator<Map.Entry<Long, T>> it = ring.tailMap(h, true).entrySet().iterator();
        Iterator<Map.Entry<Long, T>> wrap = ring.entrySet().iterator();
        Set<T> seen = new LinkedHashSet<>();
        while (seen.size() < replicas) {
            Map.Entry<Long, T> e = it.hasNext() ? it.next()
                                  : (wrap.hasNext() ? wrap.next() : null);
            if (e == null) break;                  // fewer than R distinct nodes exist
            seen.add(e.getValue());                // dedup virtual->physical collisions
            if (seen.size() == ring.values().stream().distinct().count()) break;
        }
        result.addAll(seen);
        return result.subList(0, Math.min(replicas, result.size()));
    }
}
```

Usage:

```java
ConsistentHashRing<String> ring = new ConsistentHashRing<>(200); // 200 vnodes/unit weight
ring.addNode("cache-a:11211", 1);
ring.addNode("cache-b:11211", 1);
ring.addNode("cache-c:11211", 2);  // double weight -> ~half the keyspace

String server = ring.getNode("user:42:profile");      // route a cache key
List<String> replicas = ring.getNodes("order:9912", 3); // 3 nodes for replication
```

### 5.2 Measuring rebalancing: prove only ~1/N keys move

```java
import java.util.*;

/**
 * Use case: a test/benchmark that quantifies key movement on node add,
 * comparing consistent hashing to modulo hashing.
 */
public class RebalanceProof {
    public static void main(String[] args) {
        int keys = 1_000_000;
        List<String> sampleKeys = new ArrayList<>(keys);
        for (int i = 0; i < keys; i++) sampleKeys.add("key-" + i);

        // ---- Consistent hashing ----
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(200);
        for (int i = 0; i < 10; i++) ring.addNode("node-" + i, 1);

        Map<String, String> before = new HashMap<>();
        for (String k : sampleKeys) before.put(k, ring.getNode(k));

        ring.addNode("node-10", 1); // 10 -> 11 nodes

        long moved = sampleKeys.stream()
            .filter(k -> !before.get(k).equals(ring.getNode(k))).count();
        System.out.printf("Consistent: %.2f%% moved (expected ~%.2f%%)%n",
            100.0 * moved / keys, 100.0 / 11);

        // ---- Modulo hashing for contrast ----
        long modMoved = sampleKeys.stream()
            .filter(k -> (Math.floorMod(k.hashCode(), 10))
                       != (Math.floorMod(k.hashCode(), 11)))
            .count();
        System.out.printf("Modulo:     %.2f%% moved (expected ~%.2f%%)%n",
            100.0 * modMoved / keys, 100.0 * 10 / 11);
    }
}
// Typical output:
//   Consistent:  9.1% moved (expected ~9.09%)
//   Modulo:     90.9% moved (expected ~90.91%)
```

This is the single most convincing demonstration of why consistent hashing exists.

### 5.3 Memcached client-side sharding (Ketama-compatible)

Real memcached has no server-side clustering; the *client* shards keys across servers. Ketama is the de-facto ring algorithm. A Ketama-compatible ring lets your Java client interoperate with other Ketama clients (PHP, Python) hitting the same servers.

```java
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Use case: Ketama (libketama / spymemcached compatible) consistent hashing
 * for sharding keys across memcached servers. Each server -> 160 ring points.
 */
public class KetamaRing {
    private final NavigableMap<Long, String> ring = new TreeMap<>();
    private static final int POINTS_PER_SERVER = 160; // libketama standard
    private final MessageDigest md5;

    public KetamaRing(List<String> servers) throws Exception {
        md5 = MessageDigest.getInstance("MD5");
        for (String s : servers) addServer(s);
    }

    private void addServer(String server) {
        // Ketama: for each of 40 iterations, MD5 the "server-i" label and
        // extract 4 ring points from the 16-byte digest => 160 points total.
        for (int i = 0; i < POINTS_PER_SERVER / 4; i++) {
            byte[] digest = md5(server + "-" + i);
            for (int h = 0; h < 4; h++) {
                long point =
                      ((long) (digest[3 + h * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + h * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + h * 4] & 0xFF) << 8)
                    |  (long) (digest[h * 4]     & 0xFF);
                ring.put(point, server); // 32-bit point in a long
            }
        }
    }

    private byte[] md5(String s) {
        md5.reset();
        return md5.digest(s.getBytes(StandardCharsets.UTF_8));
    }

    public String getServer(String key) {
        byte[] d = md5(key);
        long h = ((long) (d[3] & 0xFF) << 24) | ((long) (d[2] & 0xFF) << 16)
               | ((long) (d[1] & 0xFF) << 8) | (long) (d[0] & 0xFF);
        Map.Entry<Long, String> e = ring.ceilingEntry(h);
        return (e != null ? e : ring.firstEntry()).getValue();
    }
}
```

The byte-extraction order (little-endian per 4-byte group) matches libketama; matching it exactly is what makes a Java client land on the *same* server as a PHP/Python client for the same key.

### 5.4 Rendezvous (HRW) hashing — ring-free, easy replication

```java
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Use case: route keys to a SMALL set of backends (e.g. a CDN edge picking
 * among a handful of origins) with minimal disruption and trivial top-R selection.
 * O(N) per lookup; ideal when N is small/medium.
 */
public class RendezvousHashing {
    private final List<String> nodes = new ArrayList<>();

    public void addNode(String n) { nodes.add(n); }
    public void removeNode(String n) { nodes.remove(n); }

    /** Combine key+node into a 64-bit weight; pick the highest. */
    private long weight(String key, String node) {
        return Hashing.murmur3_128()
            .newHasher()
            .putString(key, StandardCharsets.UTF_8)
            .putString(node, StandardCharsets.UTF_8)
            .hash().asLong();
    }

    public String getNode(String key) {
        String best = null; long bestW = Long.MIN_VALUE;
        for (String n : nodes) {            // O(N): score every node
            long w = weight(key, n);
            if (w > bestW) { bestW = w; best = n; }
        }
        return best;
    }

    /** Top-R nodes for replication: the R highest scores, already distinct & ordered. */
    public List<String> getNodes(String key, int r) {
        return nodes.stream()
            .sorted(Comparator.comparingLong((String n) -> weight(key, n)).reversed())
            .limit(r)
            .collect(Collectors.toList());
    }
}
```

Why HRW shines for replication: there is no "skip duplicate vnode / skip same rack" gymnastics — the top-R scores are inherently distinct nodes, and weighting is done by raising scores to a power (`-1/weight · ln(uniform(hash))`) for capacity-aware variants.

### 5.5 Bounded-load consistent hashing (cap hot nodes)

```java
import java.util.*;

/**
 * Use case: protect against a node overloading due to skewed/hot keys.
 * Each node may hold at most ceil((1+eps) * avgLoad) items; overflow spills clockwise.
 * Loosely models Google's "Consistent Hashing with Bounded Loads".
 */
public class BoundedLoadRing {
    private final NavigableMap<Long, String> ring = new TreeMap<>();
    private final Map<String, Integer> load = new HashMap<>(); // current items per node
    private final double epsilon;
    private int totalItems = 0;

    public BoundedLoadRing(double epsilon) { this.epsilon = epsilon; }

    public void addNode(String node, int vnodes, ConsistentHashRing<String> hasher) {
        load.putIfAbsent(node, 0);
        // (vnode placement omitted for brevity; reuse a ring like §5.1)
    }

    /** Assign a key, respecting the per-node cap. */
    public String assign(String key, List<Long> orderedPositions,
                         Map<Long, String> posToNode) {
        totalItems++;
        int nodeCount = (int) posToNode.values().stream().distinct().count();
        double avg = (double) totalItems / nodeCount;
        int cap = (int) Math.ceil((1 + epsilon) * avg); // max items per node

        long h = hashKey(key);
        // Walk clockwise until a node with spare capacity is found.
        NavigableMap<Long, String> tail = subRingFrom(h, posToNode);
        for (String candidate : tail.values()) {
            if (load.get(candidate) < cap) {            // has room?
                load.merge(candidate, 1, Integer::sum); // claim a slot
                return candidate;
            }
        }
        // All full (only when totalItems forces it) — fall back to the natural owner.
        String owner = tail.firstEntry().getValue();
        load.merge(owner, 1, Integer::sum);
        return owner;
    }

    // hashKey / subRingFrom are helpers over the position list (omitted).
    private long hashKey(String k) { return k.hashCode() & 0xffffffffL; }
    private NavigableMap<Long,String> subRingFrom(long h, Map<Long,String> p) {
        TreeMap<Long,String> t = new TreeMap<>(p);
        NavigableMap<Long,String> head = t.tailMap(h, true);
        // (wrap-around concatenation omitted)
        return head.isEmpty() ? t : head;
    }
}
```

The key idea is the *cap* `ceil((1+ε)·avg)` and the *clockwise spill* when the natural owner is full. Smaller `ε` = tighter balance but more spillover (more keys not on their natural owner = more lookups must also know the spill rule).

### 5.6 Topology-aware replica placement (rack/AZ awareness)

```java
import java.util.*;

/**
 * Use case: place R replicas on R distinct *failure domains* (racks/AZs), as
 * Cassandra's NetworkTopologyStrategy does, so one rack failure can't lose all copies.
 */
public class TopologyAwarePlacement {
    record NodeInfo(String id, String rack) {}
    private final NavigableMap<Long, NodeInfo> ring = new TreeMap<>();

    public List<NodeInfo> replicasFor(long keyHash, int rf) {
        List<NodeInfo> chosen = new ArrayList<>();
        Set<String> usedRacks = new HashSet<>();
        // Iterate clockwise from the key, wrapping once.
        List<NodeInfo> walk = new ArrayList<>(ring.tailMap(keyHash, true).values());
        walk.addAll(ring.headMap(keyHash, false).values());
        for (NodeInfo n : walk) {
            if (chosen.stream().anyMatch(c -> c.id().equals(n.id()))) continue; // dedup vnode
            if (usedRacks.contains(n.rack())) continue;   // one replica per rack first
            chosen.add(n); usedRacks.add(n.rack());
            if (chosen.size() == rf) break;
        }
        // If fewer racks than RF, relax the rack constraint (Cassandra does similar).
        if (chosen.size() < rf) {
            for (NodeInfo n : walk) {
                if (chosen.stream().noneMatch(c -> c.id().equals(n.id()))) {
                    chosen.add(n);
                    if (chosen.size() == rf) break;
                }
            }
        }
        return chosen;
    }
}
```

### 5.7 Thread-safe, hot-swappable ring for a live load balancer

```java
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Use case: a request router where the node set changes at runtime (autoscaling,
 * health checks). Readers must never block; updates are copy-on-write.
 */
public class LiveRouter {
    // Immutable snapshot of the ring; swapped atomically on membership change.
    private final AtomicReference<NavigableMap<Long, String>> ringRef =
        new AtomicReference<>(Collections.emptyNavigableMap());
    private static final int VNODES = 200;

    public String route(long keyHash) {
        NavigableMap<Long, String> ring = ringRef.get(); // lock-free read
        if (ring.isEmpty()) return null;
        var e = ring.ceilingEntry(keyHash);
        return (e != null ? e : ring.firstEntry()).getValue();
    }

    /** Rebuild and atomically publish a new ring (called by control plane). */
    public void updateMembership(Set<String> liveNodes) {
        TreeMap<Long, String> next = new TreeMap<>();
        for (String node : liveNodes)
            for (int i = 0; i < VNODES; i++)
                next.put(mix(node, i), node);
        ringRef.set(Collections.unmodifiableNavigableMap(next)); // publish atomically
    }

    private long mix(String node, int i) {
        return com.google.common.hash.Hashing.murmur3_128()
            .hashString(node + "#" + i, java.nio.charset.StandardCharsets.UTF_8).asLong();
    }
}
```

Copy-on-write is the standard pattern for read-heavy rings: readers see a consistent immutable snapshot with zero locking; the (rare) writer builds a fresh ring and swaps the reference.

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Lookup cost:** ring lookup is `O(log(N·V))`. For 100 nodes × 200 vnodes = 20k points, that's ~14 comparisons — nanoseconds. Don't over-engineer.
- **Memory:** a `TreeMap<Long,String>` entry costs ~48–64 bytes (node object + boxed Long + tree node). 100k points ≈ several MB. For very large rings, switch to a **sorted `long[]` of positions + parallel `int[]`/`short[]` of node indices** and `Arrays.binarySearch` — far less memory and better CPU cache behavior. Trade-off: rebuilds are `O(N·V)` and you can't cheaply mutate, so use it for read-mostly rings.
- **Hash function choice dominates throughput** if you hash on every request. Murmur3/xxHash are ~GB/s; MD5 is ~10x slower. Cache the hash of stable keys when you route the same key repeatedly.
- **Avoid recomputing the ring on every membership query.** Build once, snapshot, reuse (see §5.7).
- **Vnode count vs cost:** more vnodes = better balance but more memory and slower rebuilds. Find the smallest `V` that meets your balance SLA. Cassandra's move from 256 → 16 tokens was precisely this trade-off (256 made repair/streaming and availability worse).

### 6.2 Correctness & concurrency

- **Stable labeling forever.** The vnode label scheme (`"node#i"`, hash family, byte order) is part of your *wire/placement contract*. Changing it reshuffles the whole ring and invalidates all caches/data placement. Version it if you ever must change it, and migrate deliberately.
- **Deterministic collision handling.** Two vnodes can hash to the same position (rare in 64-bit, more likely in 32-bit). Every participant must resolve collisions *identically* (e.g. linear probe `+1`, or tie-break by node id) or different nodes will disagree on ownership — a split-brain placement bug.
- **Concurrency:** use copy-on-write (`AtomicReference` to an immutable map) for read-heavy rings, or a `ReadWriteLock`. Never mutate a shared `TreeMap` while readers traverse it (throws `ConcurrentModificationException` or returns wrong results).
- **Replica walk must dedup physical nodes and (if topology-aware) failure domains** — otherwise two "replicas" land on the same machine/rack and you silently lose redundancy.
- **Wrap-around bug** is the #1 implementation mistake: forgetting that `ceilingEntry` returns `null` when the key is past the last point, and failing to fall back to `firstEntry()`. Always test keys that hash *above* the largest vnode.

### 6.3 Security

- **Hash-flooding / algorithmic-complexity attacks:** if keys are attacker-controlled and you use a non-keyed, predictable hash, an adversary can craft keys that all land on one node, overloading it (a targeted hot-shard DoS). Mitigation: use a **keyed/seeded hash** (e.g. SipHash, or Murmur with a secret seed) so the placement is unpredictable to outsiders. This is the same class of fix Java applied to `HashMap` (treeification) and Python applied to `dict` (SipHash) for hash-DoS.
- **Don't use a cryptographic hash for *integrity* here** — consistent hashing distributes, it does not authenticate. Use TLS/MACs for that.
- **Avoid leaking topology:** if response headers or errors reveal which shard served a key, attackers can probe to find hot/weak nodes.

### 6.4 Observability

- **Per-node load distribution:** export a histogram/gauge of keys (or bytes, or QPS) per node. The ratio of max to mean is your balance health; alert if it exceeds, say, `1.3×` (with vnodes it should stay low).
- **Keys-moved-on-rebalance:** instrument membership changes to record how many keys/ranges moved; a spike beyond ~`1/N` signals a labeling/collision bug.
- **Ring snapshot endpoint:** expose the current ring (positions → nodes) for debugging (Envoy `/clusters`, Cassandra `nodetool ring`). Invaluable when a key "goes to the wrong place."
- **Hot-key detection:** track top-K keys by traffic; a single hot key defeats balance regardless of ring quality and needs application-level mitigation (key splitting, local cache, bounded-load spill).

### 6.5 Cost

- **Over-provisioning vnodes wastes memory and slows rebalancing/repair** at scale (Cassandra lesson). Right-size `V`.
- **Migration is the real cost** in stateful systems: moving `1/N` of the data on every scaling step still means streaming TBs. Schedule scaling during low-traffic windows; throttle streaming.
- **Cold-cache cost after rebalance:** even with consistent hashing, the `1/N` moved keys miss until repopulated. Budget for a transient backend load bump (it's `1/N`, not `(N-1)/N` — that's the whole win).

### 6.6 Testing

- **Property test minimal disruption:** assert that adding/removing one node moves ≈`1/N` of a large key sample (±tolerance). See §5.2.
- **Balance test:** assert max/mean load ratio stays under your SLA for your chosen `V`.
- **Determinism test:** building the ring twice from the same membership yields identical ownership for all keys; two separate processes agree.
- **Wrap-around test:** keys hashing above the top vnode resolve to the first node.
- **Replication test:** `getNodes(key, R)` returns R *distinct* nodes (and distinct racks if topology-aware).
- **Cross-client compatibility test (Ketama):** a known key must map to the same server as the reference libketama implementation.

### 6.7 Production hardening

- **Health-aware routing:** combine the ring with health checks — route to the clockwise successor that is *healthy*, skipping down nodes (this is effectively temporary removal). Envoy `ring_hash` does this.
- **Graceful joins:** new nodes should warm up (stream data) before receiving traffic; route to them only after they reach NORMAL.
- **Hinted handoff:** buffer writes destined for a temporarily-down node and replay when it returns (Dynamo/Cassandra) to avoid losing writes during transient failures.
- **Anti-entropy/repair:** after any failure or join, run repair to reconcile replicas; consistent hashing places replicas but does not keep them in sync by itself.
- **Bound the blast radius:** with very high vnode counts every node neighbors every other node, so a single failure touches *all* nodes' streaming — a correlated-load risk. Lower `V` or use rack-aware token allocation to contain it.

### 6.8 Common anti-patterns

| Anti-pattern | Why it's bad | Fix |
|---|---|---|
| `hash(key) % N` for a resizable cluster | ~`(N-1)/N` keys move on resize → cold-cache outage | Consistent hashing or rendezvous. |
| Using `String.hashCode()` / CRC32 for placement | Poor distribution, clumping, easy collisions | Murmur3/xxHash. |
| One ring point per node (no vnodes) | Severe imbalance (5x+), neighbor gets all of a dead node's load | Use 100–500 vnodes/node. |
| Forgetting wrap-around | Keys above the top vnode get no/null owner | Fall back to `firstEntry()`. |
| Changing the vnode labeling/hash later | Silent full reshuffle | Treat labeling as a stable contract; version & migrate. |
| Replicas on same node/rack | Single failure loses redundancy | Dedup physical + failure domain in the replica walk. |
| Ignoring hot keys | One key overloads its node regardless of ring | Bounded-load, key splitting, local caching. |
| Mutating a shared `TreeMap` under concurrent reads | Race/CME, wrong routing | Copy-on-write snapshot. |
| Excessive vnodes (e.g. 256+) at scale | Slow repair/streaming, every node neighbors every node | Right-size (Cassandra: 16). |

---

## 7. Advanced topics & deep internals

### 7.1 The load-balance mathematics of virtual nodes

With `N` nodes and one point each, arc lengths are the spacings of `N` uniform points on a circle, which follow a distribution where the expected max spacing is `≈ (ln N)/N` of the ring vs the mean `1/N` — a `ln N` factor imbalance. Adding `V` points per node makes each node's load the sum of `V` roughly-independent arcs; the **relative standard deviation of load falls as ≈ `1/√V`** (independent of `N` to first order). So `V=100` gives ~10% relative deviation per node; `V=400` gives ~5%. This is why "hundreds of vnodes" is the standard recommendation: it buys single-digit-percent balance cheaply. Beyond a few hundred, returns diminish while memory/repair costs keep rising.

### 7.2 Consistent Hashing with Bounded Loads (CHBL) — deep dive

Plain consistent hashing balances *expected* load but not *worst-case* load, and it cannot react to dynamic skew (some keys are hotter than others). Google's CHBL (Mirrokni et al., 2016) adds a hard cap `C = ⌈(1+ε)·(total/N)⌉` per node. Assignment:

1. Hash the key to the ring; find its natural owner clockwise.
2. If that owner's load `< C`, assign it there.
3. Else walk clockwise to the next node with load `< C`; assign there ("spill").

Guarantees: every node's load ≤ `C`, and on insertion/removal of a node or item, only `O(1/ε²)` reassignments occur. The parameter `ε` trades balance vs locality: small `ε` (e.g. 0.1) = tight balance but more spillover (more keys not on their natural owner, so deletions must also follow the spill rule and lookups need consistent state); large `ε` (e.g. 1.0) = looser cap, fewer spills, closer to plain consistent hashing. Used by Google Cloud Pub/Sub for sticky-but-bounded routing and in HAProxy/Envoy-style bounded balancing (`hash_balance_factor`). The subtlety: because assignment depends on *current* loads, the mapping is stateful — all participants/coordinators must agree on load counts, or use it where a single router owns the assignment.

### 7.3 Rendezvous (HRW) hashing — deep dive and weighting

HRW computes `score(key, node) = h(key, node)` for every node and picks the max. Properties and internals:

- **Minimal disruption:** removing a node only affects keys whose top score was that node — about `1/N` of keys, and they move to their *second-best* node, spread evenly. No vnodes needed for balance because scores are already uniform per node.
- **Top-R replication for free:** the R highest scores are the R replicas, inherently distinct and ranked.
- **Weighting (capacity):** to give node `i` weight `w_i`, transform the score: `score = -w_i / ln(uniform01(h(key,node)))` (the "Weighted Rendezvous" / log-trick), then pick the max. A node with double weight wins ~2x the keys.
- **Cost:** `O(N)` per lookup. For `N` in the dozens-to-low-hundreds this is fine and often *faster than a ring* because there's no tree and the data fits in cache. For thousands of nodes, the ring's `O(log(N·V))` wins, or use **skeleton-based HRW** (organize nodes in a tree to get `O(log N)`).
- **Where used:** CDN origin selection, GlusterFS file placement, Ceph's CRUSH is a topology-aware descendant of HRW ideas, Kubernetes/SDN load distributions, and many client libraries with small node counts.

### 7.4 Maglev hashing (Google's LB hashing)

**Maglev** (Google's network load balancer, 2016) uses a **fixed-size lookup table** (a prime, e.g. 65537 or 65521 entries) populated by a deterministic permutation per backend, instead of a sorted ring. Lookups are `O(1)` array indexing (`table[hash(key) % tableSize]`). It gives near-perfect balance and minimal disruption on backend changes (a small fraction of table entries change), trading a tiny bit more disruption than a true ring for `O(1)` lookups and a compact, cache-friendly table. Envoy implements it as the `MAGLEV` LB policy (default table size 65537). Use Maglev when you want ring-like properties but `O(1)` lookups and a fixed memory footprint.

### 7.5 Jump consistent hash (Lamping & Veach, Google, 2014)

A remarkable ring-free algorithm: maps a 64-bit key and a bucket count to `[0, buckets)` in ~`O(ln(buckets))` arithmetic steps, **no memory, no data structure**, perfectly balanced, and minimal movement *when buckets grow*. Guava exposes it as `Hashing.consistentHash(long key, int buckets)`.

```java
import com.google.common.hash.Hashing;
int bucket = Hashing.consistentHash(keyHash64, numBuckets); // 0..numBuckets-1
```

**Big limitation:** it only supports buckets numbered `0..n-1` and only handles *growing or shrinking the count from the top* — you cannot remove an *arbitrary* bucket (e.g. node #3 dies but #0..#7 stay). So it fits "increase shard count" scenarios (like resharding a fixed-id partition set) but not "this specific server died." When nodes have stable identities and arbitrary failures, use a ring or HRW instead.

### 7.6 Token allocation algorithms (beyond random vnodes)

Random vnode placement is simple but leaves residual imbalance and can place replicas poorly. Production systems use smarter allocation:

- **Cassandra `allocate_tokens_for_local_replication_factor`:** instead of random tokens, the new node picks tokens that *minimize the variance of ownership for the given RF*, dramatically improving balance at low `num_tokens` (this is what made `num_tokens=16` viable). It is a greedy optimization over the existing ring.
- **DynamoDB's evolution:** the original Dynamo paper described three partitioning strategies; production DynamoDB moved toward fixed equal-sized partitions per node (strategy 3) for predictable balance, away from purely random token-per-node.
- **Riak:** fixed `ring_creation_size` (power of two) partitions distributed evenly, with claim algorithms to spread partitions across physical nodes and avoid adjacent ownership.

### 7.7 Replication, the ring, and correlated failure

A subtle interaction: with many vnodes, each physical node becomes a clockwise neighbor of *many* others. For replication-by-successor, this means a node's replica partners are spread across (potentially all) other nodes. If any *two* nodes fail, the probability that some key had both its surviving replicas... actually had two of its three replicas on those two nodes rises with vnode count — i.e. **more vnodes increase the chance that a random pair of failures causes data loss for *some* token range**. This is a documented reason Cassandra lowered default `num_tokens`. Topology-aware placement (one replica per rack) and lower `V` mitigate it. The general principle: vnodes improve *balance* but can worsen *correlated-failure blast radius*; tune with both in mind.

### 7.8 Hot keys defeat any ring

Consistent hashing balances the *key space*, not the *traffic*. If 90% of requests hit one key, that key's node is overloaded no matter how perfect the ring. Mitigations live above the hashing layer: (a) **key splitting** — append a random suffix to spread a hot key across N sub-keys and fan-in on read; (b) **client-side / edge caching** of hot keys; (c) **bounded-load spill** (§7.2) to cap per-node load; (d) **request coalescing** to collapse duplicate concurrent misses. Always measure traffic skew, not just key-count skew.

### 7.9 Anchor hashing, multi-probe, and newer schemes (brief)

- **Multi-probe consistent hashing (MPCH):** instead of many vnodes, hash the key `k` times and pick the closest node, getting good balance with *one* point per node — less memory than vnodes at the cost of `k` hashes per lookup.
- **Anchor hashing / DxHash / Maglev-like schemes:** newer algorithms (2020s) that target `O(1)` lookup, minimal memory, and minimal disruption simultaneously; relevant if you operate at extreme scale and the classic ring's memory/rebuild cost bites. Treat as specialized; the ring + vnodes remains the default.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Sharding scheme comparison

| Scheme | Keys moved on resize | Lookup cost | Memory | Weighting | Arbitrary node removal | Top-R replicas | Best for |
|---|---|---|---|---|---|---|---|
| **Modulo `% N`** | ~`(N-1)/N` (terrible) | O(1) | O(1) | hard | n/a | manual | Fixed `N` only; never resizing. |
| **Consistent hashing (ring + vnodes)** | ~`1/N` | O(log(N·V)) | O(N·V) | vnode count | yes | walk clockwise (dedup) | Caches, DBs, LBs at scale; the default. |
| **Rendezvous (HRW)** | ~`1/N` | O(N) (or O(log N) skeleton) | O(N) | log-trick | yes | top-R scores (free) | Small/medium N, easy replication, CDNs. |
| **Maglev** | small (table churn) | O(1) | O(table) fixed | via table fill | yes | n/a (single pick) | LBs wanting O(1) + balance. |
| **Jump consistent hash** | minimal (grow only) | O(ln buckets) | O(1) | no | **no** (top only) | no | Growing fixed-id shard counts. |
| **Range partitioning** | depends | O(log) | O(ranges) | manual splits | yes | n/a | Ordered scans (HBase, Bigtable). |
| **Fixed hash slots (Redis Cluster: 16384)** | move slots only | O(1) | O(slots) | slot count | yes | n/a | Operationally simple, explicit slot moves. |

### 8.2 How many virtual nodes?

| Goal | Suggested V (per node) | Rationale |
|---|---|---|
| Single-digit % balance, modest cluster | 100–200 | `~1/√V` ≈ 7–10% deviation. |
| Tighter balance | 300–500 | ~5% deviation; more memory. |
| Cassandra (modern) | 16 (+ token allocation) | Balance via allocation algo, not raw count; protects repair/availability. |
| Ketama/memcached | 160 | De-facto standard. |
| Envoy ring_hash | ≥ 1024 total, scaled by weights | Default min ring size. |

### 8.3 Use when / avoid when

**Use consistent hashing when:**
- Your node set *resizes* (autoscaling, failures, planned growth) and you need cache hit rates / data placement to survive churn.
- You need **stable affinity**: the same key/session should keep hitting the same node as much as possible.
- You're building a distributed cache, KV store, sharded DB, or stateful load balancer.

**Prefer rendezvous (HRW) when:**
- Node count is small/medium and you want trivial, correct top-R replica selection and weighting without vnode bookkeeping.

**Prefer Maglev/Jump/fixed-slots when:**
- You need `O(1)` lookups (Maglev), or you only ever *grow* a fixed-id shard set (Jump), or you want explicit operator-controlled slot movement (Redis Cluster).

**Avoid consistent hashing (use modulo or range) when:**
- `N` is truly fixed forever (modulo is simpler and perfectly balanced).
- You need ordered range scans (use range partitioning).
- Traffic is dominated by a few hot keys — hashing won't save you; fix the hotness first.

### 8.4 Cache vs database: do you migrate data?

| | Cache (memcached/Redis client-side) | Stateful DB (Cassandra/Dynamo) |
|---|---|---|
| On node add | No migration; new node starts cold; old entries TTL out | Stream data to new owner before cutover |
| On node remove | No migration; misses repopulate | Reconstruct from replicas / stream out on decommission |
| Goal | Minimize *miss storm* (the `1/N` win) | Minimize *data movement* and preserve RF |
| Replication | usually none | RF=3 typical, quorum reads/writes |

---

## 9. Failure modes & debugging

### 9.1 Failure mode catalog

| Symptom | Likely cause | How to diagnose | Fix |
|---|---|---|---|
| **Cache-miss storm / DB overload right after scaling** | Modulo hashing (not consistent), or a labeling change reshuffled the ring | Compare key→node maps before/after; check if `% N` is in the routing code | Switch to consistent hashing; never change vnode labeling without migration. |
| **One node at 3–5x the load of others** | No vnodes (one point/node), too few vnodes, or a hot key | Per-node load histogram; top-K key traffic; ring snapshot showing arc sizes | Add vnodes (100–500); for hot key use splitting/bounded-load. |
| **Keys above top vnode get null/wrong node** | Missing wrap-around (`ceilingEntry`==null not handled) | Unit test with keys hashing past the last point | Fall back to `firstEntry()`. |
| **Two nodes disagree on a key's owner** | Different hash/labeling/collision-handling across clients | Run `getNode(key)` on each client for the same key; diff rings | Standardize hash family, label format, byte order, collision rule. |
| **Data loss after a single rack failure** | Replicas landed in same rack (no topology awareness) | `nodetool describering` / replica-walk inspection | Topology-aware placement (one replica per rack/AZ). |
| **More data moved than `1/N` on resize** | Collision handling overwriting vnodes, or non-stable labels | Measure keys-moved metric; expect ~`1/N` | Deterministic collision probing; stable labels. |
| **Repair/streaming takes forever, availability dips on one failure** | Too many vnodes (e.g. 256) → every node neighbors every node | `nodetool status` ownership spread; streaming session counts | Lower `num_tokens` (16) + token allocation algorithm. |
| **Targeted hot-shard DoS** | Predictable hash + attacker-chosen keys | Traffic concentrated on one node tied to specific key patterns | Seeded/keyed hash (SipHash/seeded Murmur). |
| **Split-brain placement after partial deploy** | Mixed ring versions during rollout | Compare ring version/hash across instances | Atomic ring publish; version the placement contract; coordinate rollout. |

### 9.2 Diagnosis tools & commands

- **Cassandra:** `nodetool status` (ownership %, UN/DN), `nodetool ring` (every token + owner), `nodetool getendpoints <ks> <tbl> <key>` (which nodes own a *specific* key — the fastest way to debug "where did my key go"), `nodetool describering <ks>` (token ranges + replicas), `nodetool tablehistograms`/`tpstats` (per-node load/queues), `nodetool repair` (after fixing topology).
- **Envoy:** admin `/clusters` (ring membership, per-host load/health), `/stats` for `lb_*` counters; logs for `ring_hash` ring size.
- **Application ring:** expose a debug endpoint dumping `{position → node}` and a `lookup?key=` endpoint; add metrics for per-node load, keys-moved-per-rebalance, and max/mean load ratio.
- **Reproduce balance/movement offline:** the §5.2 harness over a representative key sample is the canonical regression test and incident-reproduction tool.

### 9.3 Real-world incidents & lessons

- **The original motivation (Akamai, 1997):** Karger et al. designed consistent hashing precisely to keep web caches warm as cache servers came and went; modulo-based caching caused mass invalidation. This is the founding "incident class."
- **Amazon Dynamo (2007 paper):** introduced *virtual nodes* and *preference lists* in production after observing that naive consistent hashing gave non-uniform load and that a failed node dumped its whole load on one neighbor. The fixes — vnodes, topology-aware preference lists, hinted handoff — are now standard.
- **Cassandra `num_tokens` saga:** the long-standing default of 256 vnodes was found to (a) slow repair/streaming and (b) increase the probability that *any two* node failures cause data unavailability for some range (because every node neighbors every other). The community lowered the recommended default to 16 and shipped `allocate_tokens_for_local_replication_factor` to keep balance at low token counts. Lesson: more vnodes is not strictly better — balance vs blast-radius/operability is a real trade-off.
- **Memcached client divergence:** teams running mixed-language clients (PHP + Java) against shared memcached saw cache misses because the clients computed *different* rings (different hash/byte order). Standardizing on Ketama with identical point generation fixed it. Lesson: the placement algorithm is a cross-language contract.
- **Hot-key outages:** numerous public postmortems (celebrity user, viral item, a single popular config key) show a perfectly balanced ring still melting one node. Lesson: measure *traffic* skew; mitigate at the application layer.

---

## 10. Interview drill

> Model answers are crisp; deep-probe follow-ups go beyond recall. "Senior-signal" questions (tradeoff/justification) are marked ★.

**Q1. Why not just use `hash(key) % N` to shard?**
Because on resize (`N → N±1`) modulo remaps ~`(N-1)/N` of all keys — e.g. 99→100 nodes moves ~99% of keys, causing a cache-miss storm / massive data reshuffle. Consistent hashing moves only ~`1/N` on the same change.
- *Probe: Why exactly `(N-1)/N`?* A key keeps its bucket only if `h%N == h%(N+1)`, true for ~`1/(N+1)` of keys; the rest move.
- *Probe: When is modulo actually fine?* When `N` is fixed forever and you want simplicity + perfect balance.
- *Probe: Does consistent hashing give perfect balance?* No — only ~`1/√V` deviation with V vnodes; modulo is more even but immovable.

**Q2. Explain the ring and how a key finds its node.**
Hash nodes and keys into the same circular space; a key is owned by the first node clockwise from the key's position (smallest position ≥ key hash, wrapping to the first point if none). Each node owns the arc behind it up to the previous node.
- *Probe: What happens to ownership when a node leaves?* Its arc(s) merge into the clockwise neighbor(s); no other arcs change.
- *Probe: Data structure & complexity?* Sorted map (`TreeMap`), `ceilingEntry` → `O(log(N·V))`.
- *Probe: The classic bug?* Forgetting wrap-around when the key hashes past the last point.

**Q3. What are virtual nodes and why are they needed?**
Multiple ring points per physical node. They (1) smooth load (deviation ~`1/√V`), (2) spread a departing node's load across many neighbors instead of one, and (3) enable weighting (more vnodes = bigger share / heterogeneous capacity).
- *Probe: Tradeoff of high V?* More memory, slower repair/streaming, and increased correlated-failure blast radius (Cassandra dropped 256→16).
- *Probe: Typical V?* 100–500 generic; Ketama 160; Cassandra modern 16 + token allocation.
- *Probe: Alternative to vnodes for balance?* Multi-probe consistent hashing (k hashes, one point/node) or HRW.

**Q4. How much data moves when adding/removing a node, and why is that near-optimal?**
~`1/N` of keys. It's near-optimal because the new node must end up owning ~`1/N` of keys for balance, and those keys had to move from somewhere; you can't do better while staying balanced.
- *Probe: Where do moved keys come from with vnodes vs without?* With vnodes, evenly from all nodes; without, from one neighbor.
- *Probe: Does a cache migrate data on add?* No — it starts cold and old entries TTL out; only the `1/N` moved keys miss transiently.

**Q5. ★ Ring vs rendezvous (HRW): when would you choose each, and why?**
HRW: `O(N)` per lookup but no ring/vnode bookkeeping, perfect per-node balance, and top-R replicas come free (R highest scores, inherently distinct). Choose it for small/medium N and when you need easy replication/weighting. Ring: `O(log(N·V))`, scales to thousands of nodes, but needs vnodes for balance and a dedup walk for replicas. Choose it at large N or where lookup latency under huge node counts matters.
- *Probe: How does HRW weight nodes?* `score = -w/ln(uniform(h(key,node)))`, pick max.
- *Probe: How to make HRW O(log N)?* Skeleton/tree-organized HRW.
- *Probe: Which is easier to get correct for replication?* HRW (no skip-duplicate-vnode/rack logic).

**Q6. ★ You're designing a distributed cache that autoscales daily. Walk me through your hashing design and the failure modes you'd guard against.**
Client-side consistent hash ring, Murmur3/xxHash (seeded if keys are attacker-controlled), ~200 vnodes/node, weighting by instance size. Copy-on-write ring updated from health checks; route to the healthy clockwise successor. No data migration — rely on TTL + `1/N` transient misses; pre-warm hot keys if needed. Guard against: hot keys (split/edge-cache/bounded-load), labeling drift across clients (standardize the contract), wrap-around bug (tested), and a scaling-induced miss bump (it's only `1/N`, budgeted).
- *Probe: How do you keep multiple app instances agreeing on the ring?* Same membership source + identical, versioned placement algorithm; atomic publish.
- *Probe: When would bounded-load help?* When traffic skew threatens one node despite even key-count balance.

**Q7. ★ Argue for or against using 256 virtual nodes per node in a 50-node Cassandra cluster.**
Against, generally. 256 gives marginal balance gains over ~16-with-token-allocation but markedly worsens repair/streaming time and raises the probability that any two simultaneous failures cause unavailability for some token range (every node neighbors every other). Modern guidance is `num_tokens=16` + `allocate_tokens_for_local_replication_factor`. Pick high V only if you have wildly heterogeneous nodes and balance dominates your concerns.
- *Probe: Why does high V increase data-loss probability under double failure?* More neighbors → higher chance two failed nodes jointly hold 2 of 3 replicas of some range.
- *Probe: What fixed the low-V balance problem?* The token allocation algorithm (variance-minimizing token selection).

**Q8. How does consistent hashing place replicas, and how do you avoid losing all copies in one rack failure?**
Walk clockwise from the key, collecting the next R *distinct physical* nodes (skip duplicate vnodes). For rack/AZ safety, also skip nodes already in a used failure domain so each replica lands in a different rack/AZ (Cassandra `NetworkTopologyStrategy`).
- *Probe: What's the preference list?* Dynamo's ordered set of nodes responsible for a key (primary + successors), skipping unhealthy/duplicate nodes.
- *Probe: How does quorum tie in?* With RF=N, `W + R > N` guarantees read-your-write consistency via overlapping replica sets.

**Q9. What's bounded-load consistent hashing and when do you need it?**
A cap of `⌈(1+ε)·avg⌉` per node; if a key's natural owner is full, it spills clockwise to the next node with room. Needed when key/traffic skew would overload a node despite even key-space balance; only `O(1/ε²)` extra reassignments per change.
- *Probe: Cost of small ε?* Tighter balance but more spillover and more stateful coordination (assignment depends on current loads).
- *Probe: Where is it used?* Google Cloud Pub/Sub, Envoy/HAProxy bounded balancing.

**Q10. What is Jump consistent hash and its key limitation?**
A memory-free, `O(ln buckets)` function mapping a key+bucket-count to `[0,buckets)`, perfectly balanced, minimal movement when buckets grow. Limitation: buckets are `0..n-1` and you can only add/remove from the *top* — you cannot remove an arbitrary failed node. Good for growing fixed-id shard sets, not for stable-identity nodes with arbitrary failures.
- *Probe: API in Java?* Guava `Hashing.consistentHash(long, int)`.
- *Probe: Contrast with the ring on removal?* Ring handles arbitrary removal; Jump does not.

**Q11. Which hash function do you use for placement and why not `String.hashCode()` or CRC32?**
Murmur3 or xxHash: fast, excellent avalanche/uniformity, non-cryptographic. `String.hashCode()` and CRC32 have poor distribution/avalanche → clumping and collisions → imbalance. Use a cryptographic/keyed hash (SipHash, seeded Murmur) only to defend against hash-flooding with attacker-controlled keys.
- *Probe: Why does Ketama use MD5?* Historical: good distribution, 16 bytes → 4 ring points per hash; speed wasn't critical for cache routing.
- *Probe: Cost of switching hash families later?* Full reshuffle — it's part of the placement contract.

**Q12. ★ Your perfectly-balanced ring still has one node melting. What's happening and how do you fix it?**
A hot key (or small set of hot keys) — consistent hashing balances the key *space*, not *traffic*. Fixes (above the hashing layer): key splitting (suffix fan-out), client/edge caching of the hot key, bounded-load spill, request coalescing/single-flight to collapse duplicate misses. Diagnose with per-key traffic top-K, not just per-node key counts.
- *Probe: Would more vnodes help?* No — a single key has one position regardless of V.
- *Probe: Detection metric?* Per-key request rate / top-K, and per-node QPS vs per-node key-count divergence.

---

## 11. Glossary

- **Anti-entropy / repair:** background process that reconciles divergent replicas (e.g. Cassandra `nodetool repair`, Merkle-tree comparison).
- **Arc / range:** the contiguous slice of the ring (and thus key space) owned by a vnode/node.
- **Avalanche:** property of a hash where flipping one input bit flips ~half the output bits; good avalanche → uniform distribution.
- **Bounded-load consistent hashing (CHBL):** variant capping each node at `(1+ε)·avg` load, spilling clockwise when full.
- **Bucket:** synonym for a destination/node/shard in some literature (esp. modulo and Jump hashing).
- **Cache stampede / thundering herd:** many clients simultaneously missing the cache and overwhelming the backing store.
- **CAP theorem:** in a network partition you must choose between consistency and availability; relevant to replicated KV stores built on consistent hashing.
- **Ceiling/successor query:** find the smallest ring position ≥ a given value — implements "walk clockwise."
- **Coefficient of variation:** standard deviation ÷ mean; a unitless measure of load imbalance.
- **Cold cache:** a cache with few valid entries (high miss rate), e.g. just after mass remap.
- **Consistent hashing:** scheme placing keys and nodes on a ring so membership changes move only ~`1/N` of keys.
- **Coordinator:** node that receives a client request and orchestrates reads/writes across replicas.
- **CRUSH:** Ceph's topology-aware data placement algorithm, conceptually a descendant of rendezvous hashing.
- **Decommission:** graceful removal of a node, streaming its data out first (Cassandra `nodetool decommission`).
- **Dynamo:** Amazon's 2007 highly-available KV store; popularized vnodes, preference lists, hinted handoff, quorums.
- **Envoy:** modern L7 proxy; offers `RING_HASH` and `MAGLEV` consistent-hashing LB policies.
- **Failure domain:** a unit (rack, AZ, DC) expected to fail together; replicas should span domains.
- **FNV / MurmurHash3 / xxHash / CityHash:** fast non-cryptographic hash functions suitable for placement.
- **Gossip protocol:** peer-to-peer protocol where nodes periodically exchange membership/state (Cassandra uses it; states JOINING/NORMAL/LEAVING/DOWN).
- **Hash function:** deterministic map from input to a fixed-size integer; ideally uniform and unpredictable.
- **Hash ring / token ring:** the circular hash space onto which keys and nodes are mapped.
- **Hash slot (Redis Cluster):** one of 16384 fixed buckets; `CRC16(key) % 16384`, slots assigned to nodes (not a ring).
- **Hash-flooding / algorithmic-complexity attack:** adversary crafts keys that collide/concentrate to overload a node; mitigated by seeded/keyed hashes.
- **Highest Random Weight (HRW):** see rendezvous hashing.
- **Hinted handoff:** buffering writes for a temporarily-down node and replaying them on recovery.
- **Hot key:** a single key receiving disproportionate traffic; defeats key-space balance.
- **Jump consistent hash:** memory-free O(ln n) function mapping key→`[0,buckets)`; only top-bucket add/remove.
- **Ketama:** the standard memcached consistent-hashing algorithm (MD5, 160 points/server).
- **Maglev:** Google's LB hashing using a fixed-size lookup table; O(1) lookup, near-perfect balance.
- **Modulo (mod-N) hashing:** `hash(key) % N`; simple but remaps ~`(N-1)/N` keys on resize.
- **MVCC:** multi-version concurrency control — keeps multiple versions of data for concurrent reads/writes (adjacent topic in KV stores).
- **Multi-probe consistent hashing (MPCH):** balance via k hashes per key and one ring point per node (less memory than vnodes).
- **NetworkTopologyStrategy:** Cassandra replica placement spreading copies across racks/DCs.
- **Node / member / server / shard:** a destination that stores or serves keys.
- **`num_tokens`:** Cassandra config for vnodes per node (historically 256, modern 16).
- **Partition / shard:** subset of the key space owned by a node (an arc in the ring).
- **Preference list:** Dynamo's ordered list of nodes responsible for a key.
- **Quorum:** number of replicas a read/write must touch; `W + R > RF` gives strong consistency.
- **Rebalancing:** moving keys/data between nodes after membership change.
- **Rendezvous hashing (HRW):** for each key, score every node and pick the max; ring-free, easy top-R.
- **Replication factor (RF / R):** number of copies of each key.
- **Replica set:** the RF nodes holding a key's copies.
- **Ring point / vnode / replica (placement sense):** a single position of a node on the ring (≠ data replica).
- **Skeleton-based HRW:** tree-organized rendezvous hashing for O(log N) lookup.
- **Snitch (Cassandra):** component that informs the cluster of node topology (rack/DC) for placement.
- **SipHash:** keyed hash used to defend against hash-flooding (Python dicts, etc.).
- **Streaming (Cassandra):** transferring data ranges between nodes during join/decommission/repair.
- **Token:** a node's position(s) on the ring (Cassandra terminology; with vnodes, multiple tokens per node).
- **Virtual node (vnode):** multiple ring positions per physical node for balance/weighting.
- **Weighting:** giving higher-capacity nodes a larger key-space share (more vnodes, or HRW log-trick).
- **Wrap-around:** the ring's top (`M-1`) connects to `0`; keys past the last vnode wrap to the first.
- **ZooKeeper:** a coordination service often used to store/broadcast cluster membership; nodes watch it to learn the current node set feeding the ring.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

- **Problem:** `hash%N` moves ~`(N-1)/N` keys on resize → cache-miss storm. **Consistent hashing moves ~`1/N`.**
- **Ring:** hash keys *and* nodes into `[0, 2³²/2⁶⁴)` bent into a circle; key → first node **clockwise** (`ceilingEntry`, wrap to `firstEntry`).
- **Vnodes:** ~100–500 points/node → balance deviation `≈ 1/√V`; spread a dead node's load across many neighbors; weight = vnode count.
- **Move fractions:** add/remove one of N → ~`1/N` moved. Modulo → ~`(N-1)/N`.
- **Replication:** walk clockwise for R *distinct physical* (and distinct rack/AZ) nodes = preference list. Quorum: `W + R > RF`.
- **Hash fn:** Murmur3/xxHash (fast, uniform). Avoid `String.hashCode()`/CRC32. Seeded/SipHash if adversarial keys.
- **Data structure:** `TreeMap<Long,Node>`, `O(log(N·V))`; or sorted `long[]` + binary search for read-mostly.
- **Alternatives:** **HRW** (O(N), easy top-R, free balance), **Maglev** (O(1) table, default 65537), **Jump** (O(ln n), no arbitrary removal), **Redis Cluster** (16384 fixed slots).
- **Bounded-load:** cap `⌈(1+ε)·avg⌉`, spill clockwise; tames hot-node skew, `O(1/ε²)` extra moves.
- **Real defaults:** Cassandra `num_tokens` 256→**16** + token allocation; Ketama **160** pts/server; Envoy ring min **1024**, Maglev table **65537**; Riak `ring_creation_size` **64**.
- **Top traps:** wrap-around bug; changing the labeling contract; no vnodes (5x imbalance); replicas in same rack; too many vnodes (slow repair, bigger blast radius); hot keys (hashing won't save you).
- **Debug (Cassandra):** `nodetool status` / `ring` / `getendpoints <ks> <tbl> <key>` / `describering`; Envoy `/clusters`.

### 12.2 Self-test (no answers — recall practice)

1. Derive the expected fraction of keys that move when going from `N` to `N+1` nodes under (a) modulo hashing and (b) consistent hashing, and explain *why* each is what it is.
2. You have a 64-bit ring with 300 vnodes per node and 40 nodes. A key hashes above the largest vnode position. Trace exactly which node owns it and the code path that resolves it.
3. Explain how you would assign 3 replicas across 3 racks for a key, and what your replica-walk does when there are only 2 racks but RF=3.
4. Cassandra changed its default `num_tokens` from 256 to 16. Give two independent reasons, and explain what made 16 still achieve good balance.
5. A perfectly balanced ring shows one node at 4x QPS. List the diagnosis steps and at least three fixes, and explain why adding more vnodes does nothing here.
6. Compare rendezvous (HRW) hashing and a vnode ring for a 12-node CDN that needs top-2 replica selection and capacity weighting. Which do you pick and why?
7. When is Jump consistent hash the *wrong* choice, and what specific operational scenario does it fail to handle?
8. Describe a seeded-hash mitigation for a hot-shard DoS where keys are attacker-controlled, and explain why an unseeded Murmur3 is insufficient.
```