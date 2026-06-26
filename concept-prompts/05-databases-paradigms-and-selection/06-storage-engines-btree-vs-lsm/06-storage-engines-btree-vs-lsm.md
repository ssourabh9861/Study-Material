# Storage Engines — B-Tree vs LSM-Tree

> An exhaustive engineering-handbook chapter for a senior JVM/backend developer who wants to fully master how database storage engines work — from first principles to deep internals, tuning, debugging, and interviews.

---

## 1. Overview & where it fits

### What a "storage engine" actually is

A **storage engine** is the component of a database that is responsible for *physically* putting bytes on disk (and in memory) and getting them back again. Everything above it — the SQL parser, the query planner/optimizer, the transaction coordinator, the network protocol — eventually issues low-level requests like "store this row," "fetch the row with key 42," "give me all rows where key is between 100 and 200," or "delete this key." The storage engine answers those requests. It owns the **on-disk data structures**, the **in-memory caches/buffers**, the **write-ahead log**, the **indexes**, and the **concurrency primitives** (locks, latches, MVCC versions) that keep concurrent readers and writers from corrupting each other.

A useful mental split:

- **Query layer** (the "front of the house"): "What does the user want?" — parse SQL, plan joins, choose indexes.
- **Storage engine** (the "back of the house"): "How do I durably keep and retrieve key→value pairs efficiently?"

Almost every database — relational or NoSQL — is, at its lowest level, an **ordered key→value store**. A relational table with a primary key is a map from `primary_key → row_bytes`. A secondary index is a map from `indexed_column_value → primary_key` (or → row location). A wide-column store like Cassandra is a map from `(partition_key, clustering_key) → cell_values`. So the question "how do I store and retrieve sorted key→value pairs on durable storage, fast, under concurrency, forever" is *the* central problem, and the two dominant answers are:

1. **The B-Tree family** (specifically the **B+Tree**), used by PostgreSQL, MySQL/InnoDB, Oracle, SQL Server, SQLite, Berkeley DB, and most traditional RDBMS engines.
2. **The LSM-Tree family** (**Log-Structured Merge-Tree**), used by RocksDB, LevelDB, Cassandra, HBase, ScyllaDB, Bigtable, and (optionally) MyRocks (MySQL on RocksDB), MongoDB's WiredTiger (which actually supports *both*), and many newer "NewSQL"/cloud-native stores.

> **Terminology note up front.** Throughout this doc "B-Tree" is shorthand for the **B+Tree** variant that real databases use, unless I explicitly say "classic B-Tree." The distinction (covered in §2) is that a B+Tree stores all actual values only in the leaf level and chains the leaves together; a classic B-Tree stores values in interior nodes too. Databases overwhelmingly use B+Trees.

### The problem they both solve

Disks — whether spinning hard drives (HDDs) or solid-state drives (SSDs) — are dramatically slower than RAM and have very different performance characteristics for **sequential** vs **random** access. Both engine families are fundamentally strategies for **mapping a logical sorted map onto block-oriented persistent storage while minimizing expensive disk operations.** They differ in *which* disk operations they choose to minimize:

- **B-Trees update data in place.** To change a value, the engine finds the page that holds it and rewrites that page (mostly random writes, but the *page itself* is read-then-written). This makes B-Trees naturally good at reads (a key is in exactly one place) and at in-place point updates, at the cost of write amplification from rewriting whole pages and doing random I/O.
- **LSM-Trees never update in place; they only append.** Writes go into an in-memory buffer and are later flushed as new immutable sorted files; old data is superseded and eventually garbage-collected by a background process called **compaction**. This turns writes into large sequential writes (very fast, especially on disks that hate random writes), at the cost of reads possibly having to look in several places, and of background CPU/IO spent on compaction.

The one-line tension, which you should burn into memory:

> **B-Tree = read-optimized, write-in-place, lower read amplification, higher write amplification on small random writes.
> LSM-Tree = write-optimized, append-only, higher read amplification, lower write amplification, but pays it back as background compaction.**

### When you reach for each (quick version; full framework in §8)

- **Reach for a B-Tree** when your workload is **read-heavy or read/write-balanced**, you need **strong, low-latency point reads and range scans**, you want **predictable latency** (no compaction stalls), you rely heavily on **transactions with rich isolation**, and your write volume fits comfortably on your hardware. This is the safe default for OLTP (online transaction processing) on relational data.
- **Reach for an LSM-Tree** when your workload is **write-heavy / ingest-heavy** (time-series, event logs, metrics, IoT, high-velocity inserts/upserts), you can tolerate slightly higher and more variable read latency, you want **better write throughput and better space efficiency through compression**, and you're often on SSDs where avoiding random writes also **prolongs device life** (fewer program/erase cycles).

### One-paragraph mental model

Think of a **B-Tree** as a meticulously maintained, balanced filing cabinet: every key has exactly one drawer, the drawers are kept sorted and roughly equally full, and when you change a record you walk to its drawer and edit it in place — fast to find anything, but each edit means opening and rewriting a whole drawer. Think of an **LSM-Tree** as a busy mail room with an inbox: new and changed records are just dropped into a fast in-memory inbox (the **memtable**); when the inbox fills, its sorted contents are written out as a brand-new, never-edited sorted file (an **SSTable**); to read a record you check the inbox first, then the newest files, then older ones (using **Bloom filters** to skip files that can't contain your key); and a background crew (**compaction**) continuously merges the piles of files, throwing away superseded and deleted records to keep reads from degrading and reclaim space. The B-Tree pays its cost at write time and read time predictably; the LSM defers cost to a background process and trades read sharpness and latency stability for write throughput.

---

## 2. Foundations from first principles

This section builds the vocabulary and the storage model that both engines stand on. If you already know what a page, a buffer pool, and a WAL are, skim — but the LSM-specific terms (memtable, SSTable, compaction, Bloom filter) and the amplification definitions matter for everything after.

### 2.1 The storage hierarchy and why it dictates everything

Hardware has a steep latency hierarchy. Approximate, order-of-magnitude **2020s** numbers (these move over time and vary by hardware — treat as ratios, not gospel):

| Level | Typical latency | Notes |
|---|---|---|
| CPU register / L1 cache | ~1 ns | |
| Main memory (RAM / DRAM) | ~100 ns | Random access is fine. |
| NVMe SSD (read) | ~10–100 µs | ~100–1000× slower than RAM. Random reads OK. |
| SATA SSD | ~100 µs | |
| Spinning HDD (random) | ~5–10 ms | A *seek* (moving the head) dominates; ~100–200 random IOPS. |
| HDD (sequential) | high MB/s | Sequential throughput is fine; **random is the killer.** |
| Network round trip (same DC) | ~0.5 ms | |

Two consequences drive storage-engine design:

1. **Disk is block-addressed, not byte-addressed.** You cannot read or write a single byte from disk; you read/write whole **blocks** (a.k.a. **sectors**/**pages**). The OS and device move data in fixed chunks (commonly 512 B sectors, 4 KB OS pages, and database pages of 4–32 KB). So reading one row costs *at least* one block I/O, and writing one byte costs reading then writing a whole block.
2. **Sequential I/O is vastly cheaper than random I/O**, dramatically so on HDDs (no seeks) and still meaningfully so on SSDs (better queueing, larger transfers, fewer flash translation operations, less write amplification at the device level). This single fact is the entire reason LSM-Trees exist: they convert random writes into sequential writes.

> **Beginner aside — what is a "page"?** A **page** (or **block**) is the fixed-size unit of data a database reads/writes at once. PostgreSQL uses 8 KB pages by default; MySQL/InnoDB uses 16 KB; SQLite defaults to 4 KB. The engine never deals in single rows on disk — it deals in pages. A page typically holds many rows. Choosing a page size trades off: bigger pages amortize per-I/O overhead and fit more keys per node (shallower trees) but waste more space and bandwidth when you only need one small row.

> **Beginner aside — what is a "syscall"?** A **system call** (syscall) is how a user-space program asks the operating-system kernel to do something privileged, like reading from disk (`pread`), writing (`pwrite`), or forcing buffered data to durable storage (`fsync`). Syscalls cross from user mode to kernel mode and are relatively expensive (microseconds), so storage engines batch I/O to amortize them.

> **Beginner aside — what is `fsync`?** When you `write()` to a file, the OS usually buffers the data in the **page cache** (RAM) and acknowledges immediately — it has *not* hit the physical disk yet. `fsync(fd)` is the syscall that forces all buffered writes for that file to durable media and doesn't return until the device confirms. Durability (the "D" in ACID) depends on `fsync` (or `fdatasync`, or direct I/O with proper flushing). It is slow (often the dominant cost of a commit), so engines try to `fsync` once per group of transactions, not once per write.

### 2.2 The universal abstraction: an ordered key→value map

Strip away SQL and both engines implement the same interface:

```
put(key, value)          // insert or overwrite
get(key) -> value        // point lookup
delete(key)              // remove
scan(startKey, endKey)   // ordered range iteration
```

**Why ordered (sorted by key) and not just a hash map?** A hash index gives O(1) point lookups but cannot do range scans (`WHERE id BETWEEN 100 AND 200`), cannot do `ORDER BY` cheaply, and cannot do prefix/range queries. Databases need ordered access, so both B-Trees and LSM-Trees keep keys **sorted**. (Hash indexes do exist — e.g., MySQL MEMORY engine, PostgreSQL hash indexes — but they are special-purpose, not the primary table structure.)

### 2.3 Write-Ahead Logging (WAL) — durability for both families

Both families almost always use a **Write-Ahead Log (WAL)** for crash recovery. The rule, **WAL protocol**: before you modify the actual data structures (on disk or even in memory in a way you'll acknowledge), you first **append a record of the change to a sequential log file and `fsync` it.** Because the log is appended sequentially, the `fsync` is cheap relative to random data writes. If the process crashes, on restart the engine **replays** the WAL from the last known-good checkpoint to reconstruct any changes that hadn't yet been written to the main data files.

- In **B-Trees**, the WAL (PostgreSQL calls it the **WAL**; InnoDB calls it the **redo log**; Oracle the **redo log**; SQL Server the **transaction log**) protects the dirty pages sitting in memory that haven't been flushed yet.
- In **LSM-Trees**, the WAL (often called the **commit log**, e.g., in Cassandra) protects the **memtable** — the in-memory write buffer — which would otherwise be lost on crash before it's flushed to an SSTable.

> **Beginner aside — what is a "checkpoint"?** A **checkpoint** is a point at which the engine flushes enough dirty in-memory state to disk and records "everything up to log position X is safely persisted." Recovery then only needs to replay the log *after* the last checkpoint, bounding recovery time. Checkpointing too often hurts throughput (extra flushing); too rarely lengthens crash recovery and grows the log.

### 2.4 The buffer pool / page cache — memory in front of disk

Because disk is slow, both families keep hot data in RAM. A **buffer pool** (InnoDB term; PostgreSQL: **shared buffers**) is an in-process cache of pages. Reads check the buffer pool first (a **hit**) and only go to disk on a **miss**. Modified pages become **dirty** and are written back later. B-Trees lean heavily on the buffer pool for both reads and writes; LSM-Trees use a **block cache** for read-side SSTable blocks plus the OS page cache, but their *writes* go through the memtable rather than dirtying random pages.

### 2.5 The three amplification factors (the scorecard for any storage engine)

These three metrics are how engineers compare storage engines. Memorize them — they recur in every comparison and interview.

1. **Read Amplification (RA):** the number of disk reads (or bytes read) required to serve **one logical read**. If a point lookup must check a memtable, then several SSTable files at different levels, that's high read amplification. A B-Tree point lookup reads one path root→leaf (a handful of pages), so its read amplification is the tree height, typically 3–4.

2. **Write Amplification (WA):** the number of bytes physically written to storage per **one logical byte written by the application.** If you write a 100-byte row but the engine rewrites a 16 KB page, that's WA ≈ 160 for that operation. In LSM-Trees, the same data is rewritten multiple times as it's compacted from level to level, so each byte may be physically written 10–30× over its lifetime. Write amplification matters for **throughput** (you're bandwidth-limited) and for **SSD endurance** (flash wears out after a finite number of program/erase cycles).

3. **Space Amplification (SA):** the ratio of bytes used on disk to the actual logical size of the live data. Causes: a B-Tree's pages are typically only ~⅔ full (fragmentation, see §3.1); an LSM-Tree temporarily holds superseded/deleted records until compaction reclaims them, and during compaction may hold two copies. SA matters for **storage cost** and capacity planning.

> **The fundamental theorem (informal — the "RUM conjecture").** You cannot simultaneously minimize all three of Read, Update (write), and Memory (space) amplification. Optimizing one tends to worsen another. B-Trees pick *low read amplification* and *predictable space*, paying with write amplification on small writes. LSM-Trees pick *low write amplification on ingest* (sequential, deferred), paying with read amplification and compaction overhead, and they can choose where to sit on the read-vs-space curve via compaction strategy. There is no free lunch; you choose your poison to match your workload. (This is the **RUM conjecture**: Read-, Update-, Memory-overhead — pick two to optimize, the third suffers.)

### 2.6 Glossary of the must-know LSM terms (defined here, used throughout)

- **Memtable:** an in-memory, sorted, mutable structure (commonly a balanced BST / **skip list**) that absorbs all incoming writes. Fast because it's RAM. Backed by the WAL for durability.
  > **Beginner aside — what is a skip list?** A **skip list** is a probabilistic sorted data structure: a linked list with multiple "express lane" layers that let you skip ahead, giving O(log n) search/insert on average without the rebalancing complexity of a tree. It's popular for memtables because it's simple, supports concurrent reads/writes well, and keeps keys sorted for cheap flushing.
- **SSTable (Sorted String Table):** an **immutable**, sorted-on-disk file of key→value pairs. "Immutable" means once written it is never modified — only created and later deleted. Internally it has a data section (sorted records, often in compressed blocks) plus an index and metadata (min/max key, Bloom filter, etc.). The name comes from Google's Bigtable paper.
- **Flush:** the act of writing a full memtable out to disk as a new SSTable.
- **Compaction:** the background process that reads multiple SSTables, merges them (keeping the newest version of each key, dropping deletes and superseded values), and writes new, fewer/larger SSTables — then deletes the inputs. This is the GC of an LSM-Tree.
- **Tombstone:** a special marker record meaning "this key is deleted." Because LSM-Trees can't modify old files, a delete is written as a tombstone that shadows older values; the actual data (and the tombstone itself) is purged later during compaction. Tombstones are a notorious source of trouble (see §9).
- **Bloom filter:** a compact, probabilistic set membership structure attached to each SSTable that answers "is key K *possibly* in this file?" with **no false negatives** and a tunable **false-positive** rate. It lets reads skip SSTables that definitely don't contain the key, slashing read amplification. (Full mechanics in §3.2.6.)
- **Level / tier:** organizational layers of SSTables. **Leveled compaction** keeps non-overlapping sorted runs per level with each level ~10× the previous; **size-tiered** groups similarly sized SSTables into tiers and merges a tier when it accumulates enough files. (Full treatment in §3.2.8.)

---

## 3. How it works internally (the heart of the document)

We go deep on each family separately, then on the cross-cutting machinery (Bloom filters, compaction, MVCC).

### 3.1 B+Tree internals

#### 3.1.1 Structure: nodes, pages, fan-out, height

A **B+Tree** is a balanced, **n-ary** (many-children-per-node) search tree mapped onto disk pages, one node per page:

- **Root node:** the single top node; entry point for every lookup. Usually cached permanently in memory.
- **Internal (branch) nodes:** hold **separator keys** and **child pointers** (page numbers). They contain *no* row data — only routing information: "keys < 50 go left, keys in [50,100) go to the middle child," etc.
- **Leaf nodes:** hold the actual key→value pairs (or, for a secondary index, key→primary-key/row-pointer). In a B+Tree, **all real data lives only in the leaves.** Leaves are typically **doubly linked** (each leaf points to its left and right sibling) so that **range scans** can walk sideways across leaves without going back up through the root.

**Fan-out** is the number of children an internal node can have, which equals roughly (page size) / (key size + pointer size). With an 8–16 KB page and, say, 16-byte keys + 8-byte pointers, fan-out is in the **hundreds** (often 100–1000+). High fan-out means very **shallow** trees.

**Height math:** with fan-out *f* and *N* keys, height ≈ log_f(N). With f = 500:
- 1 level holds ~500 keys,
- 2 levels ~250,000,
- 3 levels ~125 million,
- 4 levels ~62 billion.

So a B-Tree indexing **billions** of rows is typically only **3–4 levels deep.** A point lookup therefore touches only 3–4 pages, and with the upper levels cached in the buffer pool, often only the **leaf** read actually hits disk. **This is why B-Trees have such low, predictable read amplification.**

> **What "balanced" means and why it matters.** "Balanced" means every leaf is at the same depth, so every lookup costs the same number of hops regardless of key — no pathological slow keys. The B-Tree maintains this invariant automatically on every insert/delete via splits and merges (below). A naive binary search tree, by contrast, can degenerate to a linked list (O(n)) under sorted inserts; B-Trees never do.

#### 3.1.2 The lifecycle of a read (point lookup)

Step by step, `get(key=K)`:

1. **Start at the root** (in buffer pool). Binary-search its separator keys to find which child subtree could contain K.
2. **Descend** to that child (internal node). If it's not in the buffer pool, **read its page from disk** (a miss). Binary-search again, pick the next child.
3. **Repeat** until you reach the **leaf**. Read the leaf page (often the only real disk I/O).
4. **Binary-search the leaf** for K. If present, return its value; if absent, return "not found."

Total disk reads ≤ tree height (3–4), minus whatever was cached. To protect the page from being changed mid-read by a concurrent writer, the read takes a short-lived **latch** (a lightweight mutex on the page; see §3.1.7) using a technique called **latch crabbing/coupling**: latch the child before releasing the parent, so you never hold the whole path, only two adjacent levels at a time.

#### 3.1.3 The lifecycle of a range scan

`scan(start, end)`:

1. **Descend** root→leaf to find the leaf containing `start` (same as a point lookup).
2. **Scan within the leaf** from `start` forward, emitting matching keys.
3. When you hit the end of the leaf, **follow the right-sibling pointer** to the next leaf (no need to go back up). Continue until you pass `end`.

Because leaves are physically grouped and sibling-linked, a range scan is *mostly* sequential — but **only if the leaves are physically contiguous on disk.** Over time, splits scatter leaves (fragmentation), so logical order ≠ physical order, and a "sequential" scan becomes random I/O. This is why B-Tree engines periodically **defragment/rebuild** indexes and why `CLUSTER`/`OPTIMIZE TABLE` exist. (LSM SSTables are always physically sorted within a file, so they don't suffer this *within* a file — but they may have to merge across files.)

#### 3.1.4 The lifecycle of a write (insert/update) — the in-place model

`put(K, V)` (assume an update of an existing key first, then insert):

1. **WAL first.** Append a redo log record describing the change and (at commit, per the durability config) `fsync` the log. This is the durability guarantee; the data page can be written lazily.
2. **Find the target leaf** by descending root→leaf (read path, but now taking write latches near the leaf).
3. **Modify the leaf in place** in the buffer pool: overwrite the value (update) or insert the new key in sorted position (insert). Mark the page **dirty**.
4. **Acknowledge** the write to the client (durability already guaranteed by the WAL fsync). The dirty page is written back to its home location later by a **background page flusher / checkpointer** — possibly batching many changes per page into a single physical write.

If the leaf has room, that's it. If not, we **split** (next).

#### 3.1.5 Node splits and merges (how balance is maintained)

**Split (on insert into a full node):**
1. Allocate a **new page**.
2. Move the **upper half** of the keys from the full node into the new page (now both are ~half full).
3. **Push up** the middle separator key into the **parent**, with a pointer to the new page.
4. If the parent is now full, **split the parent too**, recursively, possibly all the way to the **root**. Splitting the root creates a new root and **increases tree height by one** — the only way a B-Tree grows taller.

**Merge / rebalance (on delete that under-fills a node):** if a node drops below the minimum fill (often ~½), the engine either **borrows** a key from a sibling (redistribution) or **merges** two siblings into one and removes a separator from the parent, possibly cascading upward and shrinking height. (In practice many engines defer or skip aggressive merging — InnoDB and PostgreSQL tolerate underfull pages rather than constantly merging, accepting some space amplification to avoid write/contention overhead.)

> **Page split = write amplification + latency spike.** A split rewrites at least two leaf pages plus a parent (and maybe more up the tree), all logged to the WAL. Under heavy random insert, splits are frequent and cause both extra writes and **brief locking of larger subtrees**, producing latency outliers. Inserting in **ascending key order** (e.g., auto-increment PK) avoids most random splits — new rows always go to the rightmost leaf, which splits cleanly — which is exactly why monotonic primary keys are recommended for B-Tree tables. Random PKs (e.g., random UUIDv4) cause splits all over the tree and **page cache thrash** — a classic anti-pattern (§6).

#### 3.1.6 Free space, fill factor, and fragmentation

- New/split pages are typically left **~⅔ full** (a tunable **fill factor**, e.g., PostgreSQL `fillfactor`, InnoDB `MERGE_THRESHOLD`/`innodb_fill_factor`) so future inserts have room without immediate splits. This deliberate slack is a main source of **space amplification** in B-Trees (~33% overhead is common).
- **Fragmentation** accumulates: logically adjacent leaves end up physically scattered; pages become partly empty after deletes. Remedies: `REINDEX`/`OPTIMIZE TABLE`/`VACUUM` (Postgres), `OPTIMIZE TABLE` (InnoDB), index rebuilds. These rewrite the structure compactly — expensive, often needing a maintenance window or online-rebuild support.

> **Beginner aside — PostgreSQL `VACUUM` and bloat.** PostgreSQL uses **MVCC** (§3.3) where an UPDATE writes a *new* row version and marks the old one dead, rather than overwriting in place. Dead versions accumulate as **bloat** until `VACUUM` reclaims them (and `VACUUM` also prevents transaction-ID wraparound). So Postgres B-Trees have an LSM-flavored "garbage to collect" wrinkle that pure in-place engines like InnoDB (which does updates more in place and uses **undo logs** + **purge threads**) handle differently. Point: even "B-Tree" engines have GC concerns; the details differ.

#### 3.1.7 Concurrency in B-Trees: latches vs locks, and the WAL

- **Latches** are short-duration, lightweight mutexes protecting the **in-memory page structure** during a single operation (microseconds). They prevent two threads from corrupting a page mid-modification. Acquired/released via **crabbing** (latch child, release parent) to allow high concurrency.
- **Locks** are longer-duration, logical, protecting **transactional data** (rows/ranges) for the duration of a transaction to enforce **isolation** (e.g., two-phase locking, or coexisting with MVCC). Don't confuse them: *latches protect data structures from concurrent threads; locks protect data from concurrent transactions.*
- **Recovery:** on crash, replay the WAL/redo log forward to redo committed changes not yet flushed, and use **undo** information to roll back uncommitted changes (the classic **ARIES** algorithm: Analysis → Redo → Undo). ARIES uses a **log sequence number (LSN)** stamped on each page to know whether a logged change is already reflected in the page.
  > **Beginner aside — ARIES.** **ARIES** (Algorithm for Recovery and Isolation Exploiting Semantics) is the canonical write-ahead-logging recovery method used by most relational engines. Its key ideas: WAL, **repeating history during redo** (redo *everything* logged after the checkpoint, even uncommitted, then undo the uncommitted), and per-page LSNs to make redo idempotent.

#### 3.1.8 Why B-Trees are read-optimized (summary)

A key lives in exactly **one** place, reachable in **3–4 page reads**, with upper levels cached → tiny, **predictable** read amplification, excellent point reads, and good range scans (when not fragmented). The cost is paid on writes: in-place updates dirty whole pages, splits add write amplification, and small random writes scatter I/O.

---

### 3.2 LSM-Tree internals

The Log-Structured Merge-Tree (O'Neil et al., 1996; popularized by Google's **Bigtable** and Google's **LevelDB**/Facebook's **RocksDB**) is built on one idea: **never modify data in place; only append, and reconcile later.**

#### 3.2.1 The components

1. **WAL / commit log** (on disk, sequential): durability for in-memory writes.
2. **Memtable** (in RAM, sorted, mutable): absorbs all writes; usually a skip list.
3. **Immutable memtable(s)** (in RAM): a full memtable frozen and queued for flushing while a fresh memtable takes new writes.
4. **SSTables** (on disk, immutable, sorted): the persistent data, organized into **levels** or **tiers**.
5. **Bloom filters & block indexes** (per SSTable, mostly in RAM/block cache): to skip and seek within SSTables quickly.
6. **Compaction threads** (background): merge SSTables, drop garbage.

#### 3.2.2 The lifecycle of a write (the fast path)

`put(K, V)`:
1. **Append to WAL** and (per durability setting) `fsync`. Sequential → cheap.
2. **Insert into the memtable** (sorted, in RAM). O(log n), no disk seek.
3. **Acknowledge.** Done — typically microseconds. *Every write is a memory insert plus a sequential log append; no read-modify-write of pages, no random disk I/O on the hot path.* **This is why LSM writes are fast.**
4. An **update** to an existing key is just another `put` with a newer version; the old value still sits in some older SSTable but is now shadowed. A **delete** is a `put` of a **tombstone**.

When the memtable reaches its size threshold (e.g., RocksDB `write_buffer_size`, default **64 MB**), it is **frozen** (becomes immutable), a new memtable is created, and the frozen one is **flushed**.

#### 3.2.3 The flush

A background thread writes the immutable memtable's sorted contents to a new **SSTable** at the top of the on-disk hierarchy (Level 0 in leveled compaction). Because the memtable is already sorted, the flush is a single **large sequential write**. After the SSTable is durably written, the corresponding WAL segment can be discarded (its data is now safe in the SSTable). The new SSTable's **Bloom filter** and **index block** are built during the flush.

#### 3.2.4 The structure of an SSTable

A typical SSTable (RocksDB/LevelDB layout) contains, from front to back:
- **Data blocks:** the sorted key→value records, grouped into ~4–32 KB blocks (RocksDB default block size **4 KB**), usually **compressed** per block (Snappy/LZ4/Zstd). Compression is easy here because the data is immutable and sorted (good locality) — a big reason LSM space efficiency can beat B-Trees.
- **Index block:** maps key ranges → data block offsets, so a lookup can binary-search to the right block, then read just that block.
- **Filter block:** the **Bloom filter** (or ribbon filter) for the whole file.
- **Metadata / footer:** min/max key, sequence number range, properties, and pointers to the index and filter blocks.

#### 3.2.5 The lifecycle of a read (and why it can be expensive)

`get(K)`:
1. **Check the active memtable** (RAM). If found (including a tombstone), return.
2. **Check immutable memtables** (RAM), newest first.
3. **Check SSTables**, newest→oldest. For each candidate SSTable:
   a. **Consult its Bloom filter.** If it says "definitely not here," **skip the file entirely** (no disk I/O). This is the crucial optimization.
   b. If the Bloom filter says "maybe," use the **index block** to find the right data block, read (and decompress) that block, and binary-search it for K.
4. Return the **newest** version found (a value, or a tombstone meaning "deleted"), or "not found" if no SSTable contains K.

**Read amplification** = how many places you had to look. Without Bloom filters, a point read might touch *every* level. With Bloom filters tuned to a ~1% false-positive rate, you typically read at most ~1 unnecessary SSTable, so **point reads are nearly as cheap as a B-Tree** — *provided the key exists or the Bloom filter is well-tuned.* **Range scans are the LSM weak spot:** a scan can't use Bloom filters (they're for point membership), so it must open an iterator over the memtable and **every overlapping SSTable across every level**, merge them on the fly (a k-way merge keeping newest versions), and skip tombstones — much more work than a B-Tree's sibling-pointer walk. (Leveled compaction mitigates this by keeping at most one SSTable per key per level below L0.)

#### 3.2.6 Bloom filters in depth

A **Bloom filter** is a bit array of *m* bits plus *k* independent hash functions. To **add** key K, compute k hashes, map each to a bit position in [0, m), set those k bits. To **query** K, hash and check those k bits: if **any** is 0, K is *definitely absent* (no false negatives); if **all** are 1, K is *probably present* (could be a false positive from other keys' bits colliding).

- **False-positive probability** ≈ (1 − e^(−kn/m))^k, where n = number of keys. For the optimal k, the FPP is roughly **0.6185^(m/n)**. Rule of thumb: about **~10 bits per key → ~1% FPP**; ~15 bits/key → ~0.1%. RocksDB's default is **10 bits/key** (`bloom_bits=10`), Cassandra's default `bloom_filter_fp_chance` is **0.01** for size-tiered and **0.1** for leveled (because leveled already limits files-per-key).
- **Cost:** a few bits per key of RAM (kept in the block cache / mapped from the filter block). For a billion keys at 10 bits each that's ~1.25 GB — non-trivial; you can lower bits/key (cheaper RAM, more false positives → more wasted reads) or use space-efficient variants (**ribbon filters** in RocksDB, ~30% smaller for the same FPP).
- **No deletes:** a standard Bloom filter can't remove a key (clearing bits could break others). LSM-Trees sidestep this because SSTables are immutable — a file's filter is built once and discarded with the file. (Counting Bloom filters and Cuckoo filters support deletion but aren't typically needed here.)
- **The big payoff:** Bloom filters convert "I might have to read N levels" into "I read ~1 level," turning an LSM's worst-case read amplification into something close to a B-Tree's for **existence-based point reads**, especially for **negative lookups** (key absent), which would otherwise scan everything.

#### 3.2.7 Sequence numbers and MVCC in LSM

Every write gets a monotonically increasing **sequence number** (or timestamp, in Cassandra). When multiple versions of a key exist across SSTables/memtables, the **highest sequence number wins.** This both implements last-writer-wins reconciliation and enables **snapshot reads**: a reader pinned at sequence S sees the newest version with seq ≤ S, giving MVCC-style consistent snapshots without locking writers. Compaction must be careful not to drop versions still visible to an open snapshot.

#### 3.2.8 Compaction strategies (the defining design choice)

Without compaction, SSTables pile up forever: reads slow (more files to check), space balloons (superseded/deleted data lingers). **Compaction** merges SSTables, keeps only the newest version of each key, drops tombstones (once safe), re-compresses, and produces fewer/larger files. The **strategy** governs the read/write/space-amplification tradeoff. The two canonical families:

**A) Size-Tiered Compaction (STCS)** — used by Cassandra (default historically), HBase, ScyllaDB option.
- SSTables are grouped into **tiers by size.** When a tier accumulates **enough similarly sized SSTables** (Cassandra `min_threshold`, default **4**), they're merged into **one larger** SSTable, which then belongs to the next tier up. Like merging four small piles into one medium pile, four mediums into one large, etc.
- **Pros:** **low write amplification** (each datum rewritten relatively few times), great for **write-heavy** workloads, simple.
- **Cons:** **high space amplification** (up to ~2× transiently — a big compaction needs room for inputs + output, and multiple overlapping large SSTables can each hold a copy of a key) and **higher read amplification** (a key may live in several same-tier SSTables that overlap in key range, so a read may check many of them; Bloom filters help but a popular range can be in several files).

**B) Leveled Compaction (LCS)** — used by LevelDB (origin), RocksDB (default for many setups), Cassandra option.
- Data is organized into **levels** L0, L1, L2, … Each level has a **size budget ~10× the previous** (RocksDB `max_bytes_for_level_multiplier` default **10**). **L0 is special:** it holds freshly flushed SSTables that *may overlap* each other (since they come straight from memtables). **From L1 down, SSTables within a level are guaranteed non-overlapping** — they partition the key space into a single sorted run. So a given key appears **at most once per level** from L1 down.
- Compaction picks an SSTable in level Ln and merges it with the **overlapping** SSTables in Ln+1, producing new non-overlapping SSTables in Ln+1.
- **Pros:** **low read amplification** (at most one SSTable per level per key below L0, so a point read checks ~one file per level, ~7 levels max in practice) and **low space amplification** (typically ~10% overhead — only the bottom level is big, upper levels are tiny, little duplication).
- **Cons:** **high write amplification** — moving data down levels rewrites it ~10× per level, so total WA can be **10–30×** (each byte ends up written into each level it passes through). Heavy on disk bandwidth/CPU.

**C) Universal / Tiered+Leveled hybrids** — RocksDB **Universal compaction** (similar spirit to size-tiered, optimizes for lower WA at the cost of space), **FIFO** (just drop oldest files — for pure TTL/time-series caches), and Cassandra's **TimeWindowCompactionStrategy (TWCS)** which buckets SSTables by time window — ideal for **time-series with TTL** because whole old windows can be dropped at once without merging. RocksDB also supports **leveled with L0→L1 tiering** tweaks. ScyllaDB added **Incremental Compaction Strategy (ICS)** to reduce STCS's transient space blowup.

**Side-by-side:**

| Strategy | Write amp | Read amp | Space amp | Best for | Used by |
|---|---|---|---|---|---|
| Size-Tiered (STCS) | **Low** | High | **High** (~2× transient) | Write-heavy, insert-mostly | Cassandra (default), HBase, Scylla |
| Leveled (LCS) | High (10–30×) | **Low** | **Low** (~10%) | Read-heavy, update-heavy, space-sensitive | LevelDB, RocksDB (default), Cassandra option |
| Universal (RocksDB) | Medium | Medium-High | Medium-High | Balanced/write-leaning, lower WA than leveled | RocksDB |
| Time-Window (TWCS) | Low | Low (within window) | Low (drops whole windows) | Time-series with TTL | Cassandra |
| FIFO | ~None | Low | Low | Cache/TTL data, expiring logs | RocksDB |

#### 3.2.9 Compaction stalls / write stalls (the LSM Achilles' heel)

If writes arrive **faster than compaction can keep up**, SSTables (especially L0 files) accumulate. To prevent unbounded read amplification and space blowup, the engine **throttles or stalls writers**:
- RocksDB **slows down** writes when L0 reaches `level0_slowdown_writes_trigger` (default **20** files) and **stops** writes at `level0_stop_writes_trigger` (default **36**), and similarly on pending compaction bytes. The application sees write latency spikes or stalls.
- Cassandra shows rising **pending compactions** and SSTable counts; reads degrade.
This is the central operational pain of LSM engines: **latency is bimodal** — usually great, but occasionally terrible during compaction backlog. Mitigations: more compaction threads (`max_background_compactions`/`max_background_jobs`), rate-limiting (`rate_limiter`), faster disks, better-tuned level sizes, and **not exceeding sustainable write throughput** (the device must handle write_rate × write_amplification).

#### 3.2.10 Why LSM is write-optimized (summary)

Writes are **memory inserts + sequential WAL appends**, with the expensive reorganization deferred to **batched, sequential background compaction.** This converts many small random writes into few large sequential writes, maximizing throughput and minimizing per-write SSD wear at the moment of write — at the cost of read amplification (mitigated by Bloom filters/leveling), background CPU/IO, and occasional write stalls. The application *trades worst-case write-time work for amortized background work plus read complexity.*

---

### 3.3 Cross-cutting: MVCC, snapshots, and how both reconcile concurrency

> **Beginner aside — MVCC.** **Multi-Version Concurrency Control** lets readers and writers proceed without blocking each other by keeping **multiple versions** of a row/value. A reader sees a consistent **snapshot** (the versions that were committed as of when its statement/transaction began), while a writer creates a *new* version. Old versions are reclaimed when no snapshot needs them (Postgres `VACUUM`; InnoDB purge; LSM compaction).

- **B-Tree engines** implement MVCC by storing version chains: PostgreSQL keeps multiple tuples in the heap with `xmin`/`xmax` transaction IDs and visibility rules; InnoDB keeps the current row in place plus **undo log** records to reconstruct older versions on demand. Old versions become garbage (`VACUUM`/purge).
- **LSM engines** get MVCC almost for free from sequence numbers: each version is naturally a separate record with a sequence number; a snapshot read filters to seq ≤ S; compaction is the version GC. This natural fit is one reason LSM engines often have clean snapshot/iterator semantics.

The takeaway: **both families end up with "old version garbage to collect."** In B-Trees it's `VACUUM`/undo-purge/defrag; in LSM it's compaction. Neither truly updates "purely in place" once you add MVCC.

---

## 4. The complete toolkit

This section enumerates the knobs, APIs, and commands you actually use. Two reference toolkits: **RocksDB** (the canonical embeddable LSM engine, and what MyRocks/Kafka Streams/CockroachDB-era/TiKV/etc. build on) and the **B-Tree side** (PostgreSQL + MySQL/InnoDB). Defaults are version-specific — I flag the version where it matters and tell you when I'm unsure.

### 4.1 RocksDB (LSM) — key configuration options

| Option | Purpose | Typical default | Notes / when to change |
|---|---|---|---|
| `write_buffer_size` | Memtable size before flush | **64 MB** | Bigger = fewer, larger SSTables, fewer flushes, more RAM. |
| `max_write_buffer_number` | Max memtables (active + immutable) before stalling | **2** | Raise to absorb flush bursts. |
| `min_write_buffer_number_to_merge` | How many immutable memtables to merge on flush | 1 | >1 reduces duplicate keys flushed. |
| `level0_file_num_compaction_trigger` | L0 files that trigger L0→L1 compaction | **4** | Lower = more aggressive compaction. |
| `level0_slowdown_writes_trigger` | L0 files at which writes are throttled | **20** | Raise cautiously; risks read-amp/space. |
| `level0_stop_writes_trigger` | L0 files at which writes **stall** | **36** | Hitting this = compaction can't keep up. |
| `max_bytes_for_level_base` | Size budget of L1 | **256 MB** | Sets the level pyramid base. |
| `max_bytes_for_level_multiplier` | Per-level size growth | **10** | Larger = fewer levels, more WA per level. |
| `target_file_size_base` | Size of each SSTable in L1 | **64 MB** | Grows by `target_file_size_multiplier` per level. |
| `compaction_style` | `kCompactionStyleLevel` / `Universal` / `FIFO` | **Level** | Choose per workload (§3.2.8). |
| `compression` / `bottommost_compression` | Per-block codec | LZ4 / Zstd (build-dependent) | Zstd for best ratio, LZ4 for speed; bottommost often Zstd. |
| `block_size` | SSTable data block size | **4 KB** | Bigger = better compression, worse point-read I/O. |
| `block_cache` (`LRUCache`) | RAM cache for uncompressed blocks | (set by you) | The main read accelerator; size it generously. |
| `bloom_filter` bits/key | Bloom FPP control | **10 bits (~1%)** | Higher = fewer false-positive reads, more RAM. |
| `optimize_filters_for_hits` | Skip building filters on bottom level | false | Saves RAM when most reads hit existing keys. |
| `max_background_jobs` (flushes+compactions) | Background parallelism | **2** (older) / auto | Raise on multi-core + fast disks. |
| `rate_limiter` | Cap compaction/flush write bytes/s | none | Smooths I/O, protects foreground latency. |
| `WAL` / `manual_wal_flush` / `sync` | Durability of writes | WAL on, group-fsync | `WriteOptions.sync=true` fsyncs per write (durable, slow). |
| `disableWAL` | Skip WAL (lose durability) | false | Only for rebuildable/cache data. |
| `merge_operator` | Read-modify-write without read | none | Enables atomic counters/append (see §5). |

**Core RocksDB Java API (org.rocksdb):**

| Class / method | Purpose |
|---|---|
| `RocksDB.open(options, path)` | Open/create a DB. |
| `db.put(key, value)` / `db.put(writeOpts, key, value)` | Write a key (bytes). |
| `db.get(key)` | Point read. |
| `db.delete(key)` | Write a tombstone. |
| `db.merge(key, operand)` | Apply a registered `MergeOperator` (e.g., increment). |
| `WriteBatch` + `db.write(opts, batch)` | Atomic multi-key write. |
| `db.newIterator(readOpts)` → `seek`, `next`, `key`, `value` | Range scans / iteration. |
| `db.getSnapshot()` / `ReadOptions.setSnapshot` | Consistent snapshot reads (MVCC). |
| `ColumnFamilyHandle` | Independent keyspaces with their own options (like tables). |
| `db.compactRange(...)` | Force compaction of a key range. |
| `db.getProperty("rocksdb.stats")` etc. | Observability (see §6). |

### 4.2 PostgreSQL (B-Tree) — key knobs and commands

| Knob / command | Purpose | Default | Notes |
|---|---|---|---|
| `shared_buffers` | Buffer pool size | 128 MB (low!) | Set to ~25% RAM typically. |
| `effective_cache_size` | Planner's view of OS cache | 4 GB | Tune to ~50–75% RAM; affects index plan choices. |
| `wal_level` | WAL detail (minimal/replica/logical) | replica | logical for CDC/replication. |
| `synchronous_commit` | fsync WAL on commit? | on | `off`/`local` trades durability for throughput. |
| `checkpoint_timeout` / `max_wal_size` | Checkpoint frequency | 5 min / 1 GB | Spread I/O; affects recovery time. |
| `fillfactor` (per index/table) | Leave free space in pages | 90 (heap) / 90 (btree) | Lower for update-heavy tables to enable HOT updates / reduce splits. |
| `CREATE INDEX ... USING btree` | The default index type | btree | Also: hash, gin, gist, brin, spgist. |
| `REINDEX` | Rebuild a bloated index | — | `REINDEX CONCURRENTLY` for online. |
| `VACUUM` / `autovacuum` | Reclaim dead tuples, update stats | autovacuum on | Critical for MVCC bloat & wraparound. |
| `CLUSTER` | Physically reorder heap by an index | — | Improves range-scan locality (one-time, locks). |
| `EXPLAIN (ANALYZE, BUFFERS)` | Show plan + buffer hits/reads | — | The primary debugging tool. |
| `pg_stat_user_indexes`, `pgstattuple`, `pg_buffercache` | Index usage, bloat, cache contents | — | Observability. |

### 4.3 MySQL / InnoDB (B-Tree) — key knobs and commands

| Knob / command | Purpose | Default | Notes |
|---|---|---|---|
| `innodb_buffer_pool_size` | Buffer pool | 128 MB | Set to ~50–75% RAM on dedicated DB hosts. |
| `innodb_page_size` | Page size | **16 KB** | Set at init; 4/8/16/32/64 KB possible. |
| `innodb_flush_log_at_trx_commit` | Redo-log durability | **1** (fsync per commit) | 2/0 faster but can lose ~1s on crash. |
| `innodb_log_file_size` / `innodb_redo_log_capacity` | Redo log size | varies | Bigger = fewer checkpoints, longer recovery. |
| `innodb_io_capacity` / `_max` | Background flush rate hint | 200 / 2000 | Raise for SSD/NVMe. |
| `innodb_fill_factor` | Index page fill on build | 100 (auto for sorted) | Affects splits/space. |
| `OPTIMIZE TABLE` | Rebuild table/indexes (defrag) | — | Reclaims fragmentation. |
| `ANALYZE TABLE` | Refresh index statistics | — | For the optimizer. |
| `EXPLAIN` / `EXPLAIN ANALYZE` | Show plan | — | Debugging. |
| MyRocks (`ROCKSDB` engine) | Swap InnoDB B-Tree for RocksDB LSM | — | Same MySQL, LSM storage; for write/space-heavy workloads. |

### 4.4 Cassandra (LSM) — key knobs and commands

| Knob / command | Purpose | Default | Notes |
|---|---|---|---|
| `compaction = {class: ...}` (per table) | STCS / LCS / TWCS | STCS | Choose per workload (§3.2.8). |
| `bloom_filter_fp_chance` (per table) | Bloom FPP | 0.01 (STCS) / 0.1 (LCS) | Lower = more RAM, fewer false reads. |
| `memtable_*` settings | Memtable sizing/flush | — | Controls flush cadence. |
| `gc_grace_seconds` | How long tombstones survive before purge | **864000 (10 days)** | Must exceed repair interval to avoid zombie data. |
| `nodetool compactionstats` | Pending/active compactions | — | Spot compaction backlog. |
| `nodetool tablestats` | SSTable count, read/write latency, bloom FP ratio | — | Health check. |
| `nodetool compact` | Force major compaction | — | Use sparingly (creates one huge SSTable). |
| `nodetool flush` | Flush memtables to SSTables | — | Before maintenance. |

> **Version/vendor flags.** RocksDB defaults shift across releases (e.g., `max_background_jobs` autotuning, default compression). Cassandra 4.x changed some defaults vs 3.x. MySQL 8.0 replaced `innodb_log_file_size` semantics with `innodb_redo_log_capacity` in 8.0.30. Always confirm against your exact version's docs; where I wasn't certain I flagged it.

---

## 5. Code examples by use case

Idiomatic, runnable/adaptable Java (the reader's ecosystem) plus SQL/CLI where appropriate. Comments explain the load-bearing lines.

### 5.1 Use case: high-velocity ingest with RocksDB (LSM), tuned for writes

```java
// build.gradle: implementation "org.rocksdb:rocksdbjni:8.11.3"  // pin a real version
import org.rocksdb.*;

public class IngestStore implements AutoCloseable {
    static { RocksDB.loadLibrary(); }   // load the native (JNI) library once
    private final RocksDB db;
    private final Options opts;

    public IngestStore(String path) throws RocksDBException {
        opts = new Options()
            .setCreateIfMissing(true)
            // --- write-optimized: size-tiered/universal keeps write amp low ---
            .setCompactionStyle(CompactionStyle.UNIVERSAL)
            // bigger memtable => fewer flushes, larger sequential writes
            .setWriteBufferSize(256L * 1024 * 1024)         // 256 MB memtable
            .setMaxWriteBufferNumber(4)                      // absorb flush bursts
            .setMinWriteBufferNumberToMerge(2)              // dedup before flush
            // more background workers to keep compaction up with ingest
            .setMaxBackgroundJobs(8)
            // cheap, fast compression on hot data; heavier on cold via options below
            .setCompressionType(CompressionType.LZ4_COMPRESSION);
        db = RocksDB.open(opts, path);
    }

    // Bulk-write atomically and durably-ish; tune sync per durability needs.
    public void ingest(Iterable<byte[][]> kvPairs) throws RocksDBException {
        try (WriteBatch batch = new WriteBatch();
             WriteOptions wo = new WriteOptions()
                 .setSync(false)        // group commit; rely on WAL + periodic fsync
                 .setDisableWAL(false)) // keep WAL: crash-safe up to last flush+WAL
        {
            for (byte[][] kv : kvPairs) batch.put(kv[0], kv[1]); // append-only, fast
            db.write(wo, batch);  // single atomic, mostly-sequential write
        }
    }

    @Override public void close() { db.close(); opts.close(); }
}
```

Why this is write-optimized: large memtable + Universal compaction + batched writes + WAL group commit means each logical write is a RAM insert and the heavy reorganization is deferred to background compaction.

### 5.2 Use case: atomic counters without read-modify-write (RocksDB merge operator)

A B-Tree counter requires read row → increment → write row (a round trip and a lock). An LSM **merge operator** lets you write the *delta* and have compaction/read fold them — no read on the write path.

```java
import org.rocksdb.*;

// Register a built-in 64-bit add merge operator: db stores deltas, folds on read.
Options opts = new Options()
    .setCreateIfMissing(true)
    .setMergeOperatorName("uint64add"); // built-in associative counter merge

try (RocksDB db = RocksDB.open(opts, "/data/counters")) {
    byte[] key = "page:42:views".getBytes();
    // each request just appends a +1 delta — no read, no lock, O(1) write
    db.merge(key, longToBytes(1));   // helper encodes a little-endian uint64
    db.merge(key, longToBytes(1));
    db.merge(key, longToBytes(1));
    long views = bytesToLong(db.get(key)); // read folds all deltas => 3
}
```

This is the LSM superpower for write-heavy aggregations (counters, sums): writes never read.

### 5.3 Use case: consistent snapshot iteration (MVCC) over an LSM

```java
try (RocksDB db = RocksDB.open(new Options().setCreateIfMissing(true), "/data/kv")) {
    final Snapshot snap = db.getSnapshot();          // pin a sequence number now
    try (ReadOptions ro = new ReadOptions().setSnapshot(snap);
         RocksIterator it = db.newIterator(ro)) {     // iterator sees only seq<=snap
        for (it.seekToFirst(); it.isValid(); it.next()) {
            process(it.key(), it.value());            // concurrent writers don't affect us
        }
    } finally {
        db.releaseSnapshot(snap);  // IMPORTANT: pinned snapshots block compaction GC
    }
}
```

Note the operational hazard called out in the comment: a long-held snapshot **prevents compaction from reclaiming superseded versions**, causing space amplification — the LSM analog of a long-running Postgres transaction blocking `VACUUM`.

### 5.4 Use case: B-Tree-friendly schema in PostgreSQL (monotonic keys, range queries)

```sql
-- Monotonic primary key => inserts append to the rightmost leaf, minimal page splits.
CREATE TABLE events (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, -- ascending PK
    occurred_at timestamptz NOT NULL,
    payload     jsonb NOT NULL
);

-- B-Tree index optimized for range scans on time (leaves are sibling-linked).
CREATE INDEX idx_events_time ON events USING btree (occurred_at);

-- Reduce page splits & enable HOT updates on an update-heavy table:
ALTER TABLE events SET (fillfactor = 80);  -- leave 20% free per page for in-place updates

-- Verify the planner uses the index and observe buffer hits vs disk reads:
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM events
WHERE occurred_at BETWEEN now() - interval '1 hour' AND now()
ORDER BY occurred_at;            -- range scan walks leaf siblings; cheap on a B-Tree
```

> **Anti-pattern shown by contrast:** if `id` were a random UUIDv4 PK, inserts would scatter across the whole index, causing splits everywhere and cache thrash. Use UUIDv7 (time-ordered) or bigint identity for B-Tree PKs.

### 5.5 Use case: time-series with TTL on Cassandra (TWCS) — drop whole windows

```sql
CREATE TABLE metrics (
    sensor_id  text,
    bucket     date,
    ts         timestamp,
    value      double,
    PRIMARY KEY ((sensor_id, bucket), ts)
) WITH CLUSTERING ORDER BY (ts DESC)
  AND default_time_to_live = 2592000          -- 30 days TTL on every row
  AND gc_grace_seconds = 3600                 -- short: TTL'd data needn't wait 10 days
  AND compaction = {
        'class': 'TimeWindowCompactionStrategy',
        'compaction_window_unit': 'DAYS',
        'compaction_window_size': 1           -- one SSTable bucket per day
      };
```

TWCS buckets SSTables by day; once a day's data fully expires, the **entire SSTable is dropped** without merging — vastly cheaper than STCS/LCS for expiring time-series, and it avoids the tombstone-scan problem.

### 5.6 Use case: choosing the engine in MySQL (InnoDB B-Tree vs MyRocks LSM)

```sql
-- Read/write-balanced OLTP: InnoDB (B-Tree) — predictable latency, rich transactions.
CREATE TABLE accounts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  balance DECIMAL(18,2) NOT NULL
) ENGINE=InnoDB;

-- Write/space-heavy log ingest on the SAME server: MyRocks (LSM on RocksDB).
CREATE TABLE access_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ts DATETIME NOT NULL,
  uri VARCHAR(2048) NOT NULL,
  KEY idx_ts (ts)
) ENGINE=ROCKSDB;     -- ~2-4x better compression & write throughput than InnoDB here
```

Same SQL surface; the **storage engine** is chosen per table to match the workload — the clearest demonstration of this whole chapter.

### 5.7 Use case: micro-benchmark to *see* the difference (RocksDB random vs sequential keys)

```java
// Demonstrates LSM indifference to key order on writes (no in-place page splits),
// vs the classic B-Tree penalty for random-key inserts. Run, then compare with
// the same loop against an InnoDB table with random vs auto-increment PKs.
long n = 5_000_000;
Random rnd = new Random(1);
try (RocksDB db = RocksDB.open(new Options().setCreateIfMissing(true), "/tmp/bench");
     WriteOptions wo = new WriteOptions().setDisableWAL(true)) { // raw write speed
    long t0 = System.nanoTime();
    for (long i = 0; i < n; i++) {
        long k = rnd.nextLong();                 // RANDOM keys
        db.put(wo, longToBytes(k), VALUE_64B);   // appends to memtable regardless of k
    }
    System.out.printf("random-key puts: %d ms%n", (System.nanoTime()-t0)/1_000_000);
}
// Observe: throughput is ~flat regardless of key randomness (LSM is append-based),
// whereas a B-Tree's random-key insert rate collapses as the working set exceeds RAM.
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Match the engine to the dominant operation.** Read/scan-heavy → B-Tree; write/ingest-heavy → LSM. Measure your real read:write ratio and your **scan vs point** ratio; LSM scans are the weak spot, LSM point reads are fine with Bloom filters.
- **Keep the working set in RAM.** B-Trees collapse when the index no longer fits in the buffer pool (every random read becomes a disk seek). LSM degrades more gracefully on writes but its **block cache** and **Bloom/index blocks** must fit in RAM for good reads. Rule of thumb: size buffer pool/block cache to hold the hot index/data.
- **B-Tree: prefer monotonic keys** (auto-increment, UUIDv7) to avoid random page splits and cache thrash. **LSM: key order doesn't matter for write throughput** (appends to memtable), but **highly random keys hurt compaction locality** and block-cache hit rates on reads.
- **LSM: don't exceed sustainable throughput.** Sustainable write rate ≈ device_write_bandwidth / write_amplification. If you push past it, you hit **write stalls** (§3.2.9). Provision disks for write_rate × WA, not just write_rate.
- **Compression is an LSM advantage.** Immutable, sorted SSTables compress well (often 2–4×), reducing both space and read I/O. Use LZ4 for hot levels, Zstd for the bottommost (cold) level.

### 6.2 Correctness & concurrency

- **Understand your isolation level and how MVCC interacts.** Both families offer snapshot isolation; know whether your engine prevents write skew (serializable) and what locking it uses.
- **Long-running readers are dangerous on both.** A long Postgres transaction blocks `VACUUM` → bloat; a long RocksDB/Cassandra snapshot blocks compaction GC → space amplification. Bound transaction/snapshot lifetimes.
- **Tombstone correctness (LSM).** A delete is a tombstone that must outlive any replica that still has the old value. In Cassandra, `gc_grace_seconds` (default 10 days) must exceed your repair interval, or a deleted row can **resurrect** ("zombie data") after the tombstone is purged but an un-repaired replica still holds the value. (§9.)
- **Crash recovery.** Ensure WAL `fsync` policy matches your durability SLA. `innodb_flush_log_at_trx_commit=1`, `synchronous_commit=on`, RocksDB `WriteOptions.sync=true` give per-commit durability; the faster settings risk losing the last fraction of a second on crash.

### 6.3 Memory

- B-Tree: buffer pool (`shared_buffers` / `innodb_buffer_pool_size`) is the single biggest lever. Under-sizing it is the #1 cause of slow B-Trees.
- LSM: budget RAM across **memtables** (write_buffer_size × number), **block cache**, and **Bloom/index blocks**. Bloom filters cost ~10 bits/key — for huge datasets this is gigabytes; consider ribbon filters or `optimize_filters_for_hits`.

### 6.4 Security

- Storage engines are below auth/SQL, but **encryption at rest** matters: PostgreSQL via filesystem/TDE extensions, InnoDB tablespace encryption, RocksDB block-level encryption (via env), Cassandra transparent data encryption. Note LSM **immutability** means a deleted key's bytes persist in old SSTables until compaction — relevant for "right to be forgotten"/secure-delete requirements (you may need to force compaction to truly erase).
- WALs and SSTables can leak data if backups aren't encrypted; treat them as sensitive.

### 6.5 Observability (the actual signals to watch)

- **B-Tree (Postgres):** `EXPLAIN (ANALYZE, BUFFERS)` (shared hit vs read = cache effectiveness), `pg_stat_user_indexes` (index usage, unused indexes), `pgstattuple`/`pg_stat_all_tables.n_dead_tup` (bloat), checkpoint/WAL stats, autovacuum logs.
- **B-Tree (InnoDB):** `SHOW ENGINE INNODB STATUS` (buffer pool hit rate, pending I/O, history list length = undo backlog), `Innodb_buffer_pool_reads` vs `_read_requests`.
- **LSM (RocksDB):** `db.getProperty("rocksdb.stats")`, `rocksdb.num-files-at-levelN`, `rocksdb.estimate-pending-compaction-bytes`, `rocksdb.cur-size-all-mem-tables`, the **LOG** file's compaction stats, write-stall counters. Watch **read amplification** (files read per get) and **pending compaction bytes**.
- **LSM (Cassandra):** `nodetool compactionstats` (pending compactions — the key stall signal), `nodetool tablestats` (SSTable count, bloom false-positive ratio, tombstone counts per read), `tombstone_warn_threshold`/`tombstone_failure_threshold` log warnings.

### 6.6 Cost

- LSM typically wins on **storage cost** (better compression, ~10% space amp with leveling vs ~33% B-Tree slack) and on **SSD endurance** (sequential writes → less device-level WA → longer drive life), important at scale.
- B-Tree wins on **predictable, low operational overhead** for moderate workloads — fewer "tuning compaction" person-hours.

### 6.7 Testing

- **Test with realistic data volume and key distribution.** B-Trees behave totally differently once the index exceeds RAM; LSM behaves differently once compaction backlog forms. Toy tests mislead.
- **Test the stall/backpressure paths (LSM):** drive writes past sustainable rate and verify your app handles write slowdowns/timeouts gracefully.
- **Test tombstone/TTL reclamation (LSM):** verify deleted/expired data is actually gone after compaction; verify range scans don't blow up over tombstone-heavy partitions.
- **Test recovery:** kill -9 mid-write and confirm WAL replay restores committed data and discards uncommitted.

### 6.8 Production hardening checklist

- Right-size buffer pool / block cache to hot set.
- Set WAL durability to your SLA; use group commit.
- Monitor the family-specific stall/bloat signals (above) with alerts.
- For LSM: pick compaction strategy per table workload; cap snapshot lifetimes; provision disk bandwidth for write × WA; set Bloom FPP appropriately.
- For B-Tree: schedule/verify autovacuum (Postgres) or purge (InnoDB); use monotonic PKs; reindex/optimize on fragmentation; set `fillfactor` for update-heavy tables.

### 6.9 Anti-patterns to avoid

| Anti-pattern | Family | Why it hurts | Fix |
|---|---|---|---|
| Random UUIDv4 primary key | B-Tree | Splits everywhere, cache thrash, write amp | UUIDv7 / auto-increment |
| Buffer pool ≪ working set | B-Tree | Every read = disk seek | Size buffer pool ≥ hot set |
| Ignoring autovacuum / undo purge | B-Tree | Bloat, wraparound, slow scans | Tune/monitor vacuum |
| Range-scan-heavy workload on LSM with size-tiered | LSM | Must merge many overlapping SSTables | Use leveled compaction or a B-Tree |
| Frequent deletes/TTL without TWCS | LSM | Tombstone pile-up, scan timeouts, zombies | TWCS, tune gc_grace, partition by time |
| Long-held snapshot/transaction | Both | Blocks GC → bloat/space amp | Bound lifetimes |
| Pushing writes past sustainable rate | LSM | Write stalls, latency cliffs | Provision disk × WA; rate-limit; scale out |
| Wrong durability setting for SLA | Both | Silent data loss on crash | Match fsync policy to requirements |

---

## 7. Advanced topics & deep internals

### 7.1 B-Tree variants and refinements

- **B+Tree vs B-Tree vs B\*Tree:** B+Tree (data only in leaves, linked leaves — what databases use) maximizes fan-out and range-scan efficiency. Classic B-Tree stores data in internal nodes too (worse fan-out). **B\*Tree** keeps nodes ~⅔ full (vs ½) by redistributing before splitting, reducing space amp at the cost of more rebalancing.
- **Copy-on-write B-Trees (shadow paging):** LMDB and WiredTiger (its B-Tree mode) write modified pages to *new* locations rather than in place, atomically swapping the root pointer — giving lock-free MVCC reads and crash safety without a separate WAL (LMDB) at the cost of write amplification and a single-writer model (LMDB). This blurs the line with LSM (append-y writes) while keeping B-Tree read structure.
- **Fractal trees / Bε-trees (TokuDB, now in some engines):** add per-node **message buffers** that batch updates and push them down lazily — a hybrid that gets LSM-like write batching with B-Tree-like reads. The Bε-tree is a theoretically principled middle ground (tunable ε trades fan-out vs buffering).
- **Prefix compression & suffix truncation:** internal nodes store only the shortest distinguishing prefix of separator keys, raising fan-out (more keys per page → shallower tree). Common in InnoDB/Postgres for string keys.
- **Latch-free / Bw-tree:** Microsoft's **Bw-tree** (Hekaton, SQL Server in-memory) is a lock-free B-Tree using a mapping table and delta records — append deltas to a page's chain, consolidate later. Again, LSM-flavored deltas inside a B-Tree shape.

### 7.2 LSM advanced internals

- **L0 overlap and the "L0 trap":** L0 SSTables can overlap in key range (they come straight from memtables), so a read may check *all* L0 files. Too many L0 files (slow L0→L1 compaction) spikes read amp and triggers write stalls. Keep L0 small/compacting.
- **Compaction priority & subcompactions:** RocksDB picks which files to compact by score (how far a level is over budget) and can split a compaction into parallel **subcompactions** by key range to use multiple cores.
- **Partitioned/`max_compaction_bytes` limits** bound how much a single compaction rewrites, smoothing the I/O spikes (avoiding one giant multi-hour compaction).
- **Tombstone reclamation rules:** a tombstone can only be dropped during compaction once it's in the **bottommost level** (or proven no older data exists below) *and* past any GC grace (Cassandra) / no snapshot needs it (RocksDB). Otherwise a deleted key could reappear.
- **Range tombstones:** deleting a range (e.g., `DELETE WHERE ts < X`) as N point tombstones is catastrophic; engines support a single **range tombstone** record. Cassandra range deletes and RocksDB `deleteRange` are the right tools — but range tombstones still cost on reads until compacted.
- **Block cache nuances:** RocksDB can cache **compressed** or **uncompressed** blocks; caching index/filter blocks in the block cache (`cache_index_and_filter_blocks`) lets them share the RAM budget but risks evicting them under pressure (then reads pay to re-read filters). `pin_l0_filter_and_index_blocks_in_cache` keeps hot ones pinned.
- **Write amplification math (leveled):** total WA ≈ Σ over levels of (level multiplier) ≈ ~ (T) per level × number of levels, often quoted as ~ **T × levels** where T≈10. Real systems see 10–30× depending on level count and key churn.
- **Universal compaction space tradeoff:** lower WA than leveled but transient space amp up to ~2× (needs room for inputs+output during a big merge) — provision free disk accordingly (RocksDB `max_size_amplification_percent`).
- **Direct I/O vs page cache:** LSM engines may use `O_DIRECT` to bypass the OS page cache (avoid double-caching with the block cache) — tunable, with tradeoffs.

### 7.3 Lesser-known behaviors / gotchas

- **B-Tree "right-most leaf contention":** with a single monotonic PK and very high concurrent insert, all inserts hit the same rightmost leaf page, causing **latch contention** on that page — a real bottleneck at extreme write rates (mitigations: hash/partition the key space, or reverse-key indexes — Oracle's "reverse key index" exists for exactly this). Note this is the *opposite* failure from random keys; the sweet spot is workload-dependent.
- **LSM read amplification on *negative* lookups is the Bloom filter's whole reason for being:** a `get` for a key that doesn't exist would otherwise check every file; Bloom filters make absence cheap. Conversely, **range scans get no Bloom benefit.**
- **Compaction can evict your page cache:** a big compaction reads/writes lots of data, polluting the OS cache and evicting hot blocks → read latency spike during compaction even for unrelated keys. (`fadvise(DONTNEED)` / direct I/O mitigates.)
- **WiredTiger (MongoDB) supports both** B-Tree and LSM per collection; most use the B-Tree (default). Demonstrates that the choice is per-use-case, not per-product.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Head-to-head comparison

| Dimension | B+Tree | LSM-Tree |
|---|---|---|
| Write path | Read-modify-write page, in place; splits | Append to memtable + WAL; no in-place |
| Write throughput (random keys) | Degrades as index exceeds RAM | High & ~flat (sequential flush) |
| Write amplification | Low–moderate (page rewrite; worse on small random writes) | Moderate–high (10–30× via compaction), but sequential |
| Read amplification (point) | Low, predictable (3–4 pages) | Low *with* Bloom filters; else multi-file |
| Read amplification (range scan) | **Low** (sibling-linked leaves) | **Higher** (merge across SSTables/levels) |
| Space amplification | ~33% (page slack, fragmentation) | ~10% (leveled) to ~2× (size-tiered) |
| Latency predictability | **High** (no background stalls) | Bimodal (compaction stalls possible) |
| Compression | Harder (in-place pages) | **Excellent** (immutable sorted blocks) |
| SSD endurance | More random writes | Sequential writes → device-friendlier |
| Delete cost | In-place / mark dead | Tombstone now, real purge later |
| Background work | Vacuum/purge/checkpoint | Continuous compaction (CPU+I/O) |
| Operational complexity | Lower (mature, fewer knobs) | Higher (compaction tuning, stalls) |
| Transactions/isolation | Very mature, rich | Good (esp. RocksDB transactions), varies |
| Canonical engines | InnoDB, Postgres, Oracle, SQLite, LMDB | RocksDB, LevelDB, Cassandra, HBase, Scylla |

### 8.2 Decision rules

**Use a B-Tree when:**
- Workload is **read-heavy or balanced**, with frequent **range scans / ORDER BY / joins**.
- You need **predictable low latency** (no compaction-stall outliers) — e.g., user-facing OLTP.
- You rely on **rich transactional semantics** and a mature, low-ops engine.
- Write volume comfortably fits hardware with the working set in RAM.
- *Default choice for relational OLTP.*

**Use an LSM-Tree when:**
- Workload is **write/ingest-heavy or update/upsert-heavy** (time-series, metrics, events, logs, IoT, message queues, counters).
- You need **high sustained write throughput** and **good compression / low storage cost** at scale.
- You can tolerate **slightly higher and more variable read latency** and you do mostly **point reads / bounded scans** (not huge ad-hoc range scans).
- You're on SSDs and care about **endurance**, or you need **horizontal scale-out** with an LSM-native distributed store (Cassandra/Scylla/HBase).

**Avoid LSM when:** workload is scan-dominated with strict tail-latency SLAs and modest writes (B-Tree is simpler and faster there). **Avoid B-Tree when:** ingest exceeds what in-place random writes can sustain, or storage cost/compression dominates.

### 8.3 Alternatives & hybrids

- **Hash indexes:** O(1) point lookups, no ranges — niche (Postgres hash, MySQL MEMORY, Riak Bitcask).
- **Bε-/Fractal trees (TokuDB):** B-Tree reads + buffered LSM-like writes.
- **Copy-on-write B-Trees (LMDB, WiredTiger):** lock-free MVCC reads, append-y writes.
- **Bw-tree (Hekaton):** lock-free, delta-based B-Tree for in-memory.
- **Columnar/LSM hybrids (ClickHouse MergeTree):** LSM-like merging on column-oriented data for analytics.
- **Engine-per-table (MySQL InnoDB vs MyRocks; WiredTiger B-Tree vs LSM):** pick per workload within one product — often the right pragmatic answer.

---

## 9. Failure modes & debugging

### 9.1 B-Tree failure modes

1. **Buffer pool too small → read storm.** Symptom: latency rises as data grows; `Innodb_buffer_pool_reads` climbs vs `_read_requests`; Postgres `EXPLAIN (ANALYZE, BUFFERS)` shows many `shared read` (disk) vs `hit`. Fix: grow buffer pool, add RAM, or reduce working set.
2. **Autovacuum falling behind (Postgres) → bloat & wraparound.** Symptom: tables/indexes grow far beyond live data (`pgstattuple`, `n_dead_tup`), scans slow, "must vacuum to avoid wraparound" warnings. Real incident class: long-running transactions hold `xmin` back, preventing vacuum → runaway bloat and emergency single-user-mode wraparound recovery (famous at several companies). Fix: kill long txns, tune `autovacuum_*`, `VACUUM`/`REINDEX`.
3. **Random-key insert collapse.** Symptom: insert throughput tanks once index exceeds RAM (every insert = random read+split). Fix: monotonic keys; partition.
4. **Right-most leaf latch contention** at extreme concurrent insert on a monotonic PK. Symptom: CPU on a few latches, insert stalls. Fix: partition/hash/reverse key.
5. **Fragmentation → slow range scans.** Symptom: sequential scans do random I/O; `pgstattuple` shows low density. Fix: `REINDEX`/`OPTIMIZE`/`CLUSTER`.

Tools: `EXPLAIN (ANALYZE, BUFFERS)`, `pg_stat_*`, `pgstattuple`, `pg_buffercache`; `SHOW ENGINE INNODB STATUS`, `performance_schema`, `SHOW STATUS LIKE 'Innodb%'`; OS: `iostat`, `vmstat`, `perf`.

### 9.2 LSM failure modes

1. **Write stalls / compaction backlog.** Symptom (the classic LSM incident): write latency suddenly spikes from sub-ms to seconds; RocksDB log shows "Stopping writes" / `level0_stop_writes_trigger` reached, or `estimate-pending-compaction-bytes` huge; Cassandra `nodetool compactionstats` shows growing pending compactions. Cause: write rate > compaction throughput. Fix: more compaction threads, faster disks, rate-limit ingest, larger L0 triggers (carefully), or scale out / reduce write amp (universal compaction).
2. **Tombstone pile-up → read timeouts & zombies.** Symptom: Cassandra reads scanning a partition with thousands of tombstones blow past `tombstone_warn_threshold` (1000) and hit `tombstone_failure_threshold` (100000) → query fails; or **deleted data reappears** because `gc_grace_seconds` elapsed before repair propagated the tombstone. Famous failure class: "deleted user data came back." Fix: TWCS for TTL data, range tombstones, partition by time so old data is dropped wholesale, ensure repairs run within `gc_grace_seconds`.
3. **Read amplification from too many L0/overlapping SSTables.** Symptom: point read latency creeps up; many files read per `get`; bloom false-positive ratio high (`nodetool tablestats`). Fix: tune compaction to keep L0 small; raise Bloom bits/key; switch size-tiered→leveled for read-heavy tables.
4. **Space amplification blowup.** Symptom: disk usage ≫ logical data; size-tiered transient 2× during a big compaction fills the disk → **out-of-disk crash mid-compaction** (a real, nasty failure — a major compaction needs free space ≈ size of the data being merged). Fix: keep ≥ largest-SSTable-set free space; prefer leveled/ICS; monitor disk headroom.
5. **Long-held snapshot blocks GC → space amp.** Symptom: superseded versions can't be reclaimed; disk grows. Fix: bound snapshot/iterator lifetimes.
6. **Bloom filter / index blocks evicted under cache pressure** → read latency spike. Fix: pin filter/index blocks, size block cache, `cache_index_and_filter_blocks` tuning.

Tools: RocksDB `getProperty("rocksdb.stats")`, the DB **LOG** file (compaction/stall messages), `num-files-at-levelN`, `estimate-pending-compaction-bytes`; Cassandra `nodetool compactionstats`/`tablestats`/`tpstats`, system log tombstone warnings; OS `iostat -x` (disk %util, await), `df` (headroom), `perf`/flame graphs for compaction CPU.

### 9.3 Generic debugging method

1. **Classify the symptom:** read-slow vs write-slow vs space-grow vs latency-spiky.
2. **Check cache effectiveness** (B-Tree: buffer hit rate; LSM: block cache + bloom FP).
3. **Check background work** (B-Tree: vacuum/checkpoint; LSM: pending compactions/stalls).
4. **Check the WAL/durability path** for commit latency.
5. **Correlate with OS I/O** (`iostat` %util/await, disk free) — many "DB" problems are saturated disks.
6. **Reproduce at scale** — toy data hides B-Tree-exceeds-RAM and LSM-compaction-backlog effects.

---

## 10. Interview drill

**Q1. Explain the core difference between a B-Tree and an LSM-Tree storage engine.**
*Model answer:* A B-Tree updates data **in place** in a balanced, page-based tree — a key lives in exactly one place reachable in ~3–4 page reads, giving low, predictable read amplification but write amplification from rewriting pages and random I/O on small writes. An LSM-Tree is **append-only**: writes go to an in-memory sorted memtable + sequential WAL, are flushed as immutable sorted SSTables, and are reconciled later by background **compaction**. This makes writes fast (sequential) but reads may check multiple files (mitigated by **Bloom filters**), and it trades worst-case write work for background compaction.
- *Probe: Why is the LSM write fast specifically?* No read-modify-write of pages and no random disk I/O on the hot path — just a RAM insert plus a sequential log append.
- *Probe: Why is the B-Tree read fast?* High fan-out → shallow tree (3–4 levels) → few page reads, with upper levels cached; one canonical location per key.
- *Probe: Where does the LSM "pay" for fast writes?* Read amplification, background compaction CPU/IO, write stalls, and tombstone/space management.

**Q2. Define read, write, and space amplification and how each engine trades them.**
*Model answer:* RA = disk reads per logical read; WA = bytes written per logical byte; SA = disk bytes per logical byte. B-Tree: low RA, low-moderate WA (worse on small random writes), ~33% SA from page slack. LSM: low RA *with* Bloom filters (higher on range scans), high WA (10–30× from compaction, but sequential), SA from ~10% (leveled) to ~2× (size-tiered). The **RUM conjecture** says you can't minimize all three; you pick to match workload.
- *Probe: Why can't a range scan use Bloom filters?* Bloom filters answer point membership, not "what keys exist in [a,b]"; a scan must iterate all overlapping SSTables.
- *Probe: How does leveled compaction lower SA and RA vs size-tiered?* Levels below L0 are non-overlapping (one SSTable per key per level), so few files per read and little duplication, at the cost of more rewrites (WA).

**Q3. Walk through what happens on an LSM point read, step by step.**
*Model answer:* Check active memtable → immutable memtables (newest first) → SSTables newest→oldest; for each SSTable consult its **Bloom filter** (skip if "definitely not present"), else use the index block to read+decompress the right data block and binary-search; return the newest version found (value or tombstone) or not-found.
- *Probe: What if the key was deleted?* You find a **tombstone** (newest version) and return not-found; the tombstone shadows older values until compaction purges them.
- *Probe: Worst case without Bloom filters?* You touch every level — read amplification = number of levels/files; Bloom filters reduce this to ~1 extra file at ~1% FPP.

**Q4. What is compaction, and contrast size-tiered vs leveled.**
*Model answer:* Compaction merges SSTables, keeps newest versions, drops tombstones/superseded data, re-compresses, and deletes inputs — the LSM's GC. **Size-tiered** merges N similarly sized SSTables into one larger (low WA, high SA ~2×, higher RA) — good for write-heavy. **Leveled** keeps non-overlapping runs per level, each ~10× the previous (low RA, low SA ~10%, high WA 10–30×) — good for read/space-sensitive.
- *Probe: When would you choose TWCS?* Time-series with TTL — buckets SSTables by time window so whole expired windows drop without merging, avoiding tombstone scans.
- *Probe: What's a compaction stall?* When write rate exceeds compaction throughput, L0/pending bytes pile up and the engine throttles/stops writers (RocksDB `level0_stop_writes_trigger=36`).

**Q5. How do Bloom filters work and why are they essential to LSM reads?**
*Model answer:* A bit array + k hash functions; setting/checking k bits gives no false negatives, tunable false positives (~1% at ~10 bits/key, optimal FPP ≈ 0.6185^(m/n)). They let a read **skip SSTables that can't contain the key**, turning multi-file reads (especially negative lookups) into ~one-file reads.
- *Probe: Why can't standard Bloom filters delete keys?* Clearing bits could affect other keys; LSM sidesteps this because SSTables are immutable — the filter dies with its file.
- *Probe: Memory cost at a billion keys?* ~10 bits/key ≈ 1.25 GB; tune bits/key or use ribbon filters to shrink ~30%.

**Q6. Why do B-Trees prefer monotonic primary keys, and when does that backfire?**
*Model answer:* Ascending keys append to the rightmost leaf, which splits cleanly — minimal random splits and cache thrash. Random keys (UUIDv4) scatter inserts, causing splits everywhere and buffer-pool thrash. It backfires at extreme concurrency: all inserts contend on the **right-most leaf latch**, a hotspot — then you partition/hash or use a reverse-key index.
- *Probe: What's UUIDv7?* A time-ordered UUID that's monotonic-ish, giving B-Tree-friendly inserts while keeping global uniqueness.
- *Probe: Does key order matter for LSM write throughput?* No — all writes append to the memtable regardless of order; but very random keys hurt compaction locality and block-cache hits on reads.

**Q7. Which databases use which engine, and why?**
*Model answer:* B-Tree: PostgreSQL, MySQL/InnoDB, Oracle, SQL Server, SQLite, LMDB — relational OLTP needing read/scan performance, predictable latency, rich transactions. LSM: RocksDB, LevelDB, Cassandra, HBase, ScyllaDB, Bigtable — write/ingest-heavy, scale-out, compression-sensitive. Some products offer both: MySQL (InnoDB vs MyRocks), MongoDB/WiredTiger (B-Tree vs LSM).
- *Probe: Why might you put a log table on MyRocks but accounts on InnoDB in the same MySQL?* Log table is write/space-heavy (LSM wins compression+throughput); accounts are transactional/balanced (B-Tree predictable latency).

**Q8 (senior signal). You're designing storage for a metrics platform ingesting 1M points/sec with mostly recent-time-range reads and 30-day TTL. Which engine and config, and why?**
*Model answer:* **LSM**, e.g., Cassandra/Scylla with **TimeWindowCompactionStrategy** (daily windows), short `gc_grace_seconds`, `default_time_to_live=30d`, partition by `(series, day)`. Rationale: ingest rate demands LSM write throughput; TWCS lets whole expired day-SSTables drop without merge or tombstone scans; recent-range reads hit recent windows; compression cuts storage cost. Provision disk for write_rate × WA and monitor pending compactions. A B-Tree would collapse on random-time inserts and bloat on deletes.
- *Probe: Why not leveled compaction here?* Leveled's high WA wastes bandwidth and its strengths (low RA/SA) are unnecessary when TWCS drops whole windows; TWCS avoids constant re-merging of immutable old data.
- *Probe: What's your biggest operational risk?* Write stalls if ingest > compaction throughput, and tombstone/zombie issues if repairs lag `gc_grace_seconds`.

**Q9 (senior signal). Your Postgres OLTP DB has rising p99 latency and growing disk despite stable row count. Diagnose.**
*Model answer:* Suspect **bloat from autovacuum falling behind**, likely due to a **long-running transaction** holding back `xmin`. Confirm with `pg_stat_activity` (old `xact_start`), `n_dead_tup`/`pgstattuple` (dead tuples), and check for wraparound warnings. Buffer hit rate (`EXPLAIN ... BUFFERS`) may show more disk reads as bloat exceeds RAM. Fix: terminate the offending transaction, tune `autovacuum_vacuum_cost_limit`/`scale_factor`, `VACUUM`/`REINDEX`, and add monitoring/alerts on transaction age and dead tuples.
- *Probe: Why does a long transaction cause this?* MVCC can't reclaim row versions still potentially visible to the oldest snapshot, so dead tuples accumulate.
- *Probe: How is this analogous in LSM?* A long-held snapshot blocks compaction GC, growing space the same way.

**Q10 (senior signal). Justify choosing a B-Tree over an LSM for a new service even though writes are fairly heavy.**
*Model answer:* If the workload also has **large ad-hoc range scans, strict tail-latency SLAs, and rich transactions**, a B-Tree's predictable latency (no compaction stalls), superior range-scan performance, and lower operational complexity can outweigh LSM's write throughput — provided the working set fits RAM and the write rate is sustainable with monotonic keys. The decision is about the **whole workload profile and ops budget**, not just write volume. I'd benchmark both at realistic scale before committing.
- *Probe: What single measurement would change your mind?* If sustained write rate exceeds what in-place random writes can do (buffer pool can't keep the working set, or disk IOPS saturate), shift to LSM/MyRocks.
- *Probe: How do you de-risk the choice?* Pick a product that supports both engines (MySQL InnoDB↔MyRocks, WiredTiger B-Tree↔LSM) so you can switch per table without rewriting the app.

**Q11. What is a WAL and how does it differ in role between the two families?**
*Model answer:* A Write-Ahead Log is a sequential, fsync'd log written *before* modifying data, enabling crash recovery by replay. In B-Trees it protects dirty pages not yet flushed (redo/undo via ARIES). In LSM it protects the memtable before it's flushed to an SSTable; once flushed, the WAL segment is discarded.
- *Probe: Why is the WAL fsync cheaper than data fsync?* It's sequential append vs random data-page writes.
- *Probe: What if you disable the WAL in RocksDB?* Faster writes but you lose any data in the memtable on crash — only acceptable for rebuildable/cache data.

**Q12. Why do LSM engines achieve better compression than B-Trees?**
*Model answer:* SSTables are immutable and sorted, so blocks have strong locality and can be compressed once at write time with no need to update in place; B-Tree pages must remain individually modifiable in place and carry ~⅓ free slack, hurting compressibility and density. This makes LSM attractive for storage-cost-sensitive, large datasets.
- *Probe: Tradeoff of bigger SSTable blocks?* Better compression but more bytes read/decompressed per point lookup.

---

## 11. Glossary

- **ACID:** Atomicity, Consistency, Isolation, Durability — transaction guarantees. The "D" depends on WAL+fsync.
- **Amplification (read/write/space):** ratios of physical work/bytes to logical work/bytes; the storage-engine scorecard (§2.5).
- **ARIES:** the canonical WAL-based recovery algorithm (Analysis/Redo/Undo, per-page LSNs).
- **Block / Page:** fixed-size unit of disk I/O (Postgres 8 KB, InnoDB 16 KB, RocksDB data block 4 KB).
- **Block cache:** RAM cache of SSTable blocks in an LSM engine.
- **Bloom filter:** probabilistic set-membership structure; no false negatives, tunable false positives; lets reads skip SSTables.
- **Buffer pool / shared buffers:** in-memory cache of database pages (B-Tree engines).
- **Bε-tree / Fractal tree:** B-Tree with per-node update buffers for LSM-like write batching.
- **Bw-tree:** lock-free, delta-based B-Tree (SQL Server Hekaton).
- **Checkpoint:** point where dirty state is flushed and a recovery start marker is recorded.
- **Column family:** independent keyspace in RocksDB with its own options (like a table).
- **Compaction:** background merge of SSTables that GCs superseded/deleted data (LSM).
- **Copy-on-write B-Tree / shadow paging:** writes new pages and swaps the root (LMDB, WiredTiger).
- **Crabbing / latch coupling:** acquiring child latch before releasing parent during B-Tree traversal.
- **Fan-out:** number of children per B-Tree node; high fan-out → shallow tree.
- **Fill factor:** target page fullness; slack reduces splits but adds space amp.
- **Fragmentation:** logical order diverging from physical order / partly empty pages (B-Tree).
- **fsync / fdatasync:** syscalls forcing buffered writes to durable media.
- **gc_grace_seconds:** Cassandra window a tombstone must survive before purge (default 10 days).
- **HOT update (Postgres):** Heap-Only Tuple update that avoids index churn when no indexed column changes.
- **Latch:** short-lived, lightweight mutex protecting an in-memory structure (vs a logical lock).
- **Leveled compaction (LCS):** non-overlapping runs per level, ~10× growth; low RA/SA, high WA.
- **Lock (transactional):** longer-held mechanism enforcing isolation between transactions.
- **LSM-Tree:** Log-Structured Merge-Tree; append-only, memtable+SSTables+compaction.
- **LSN (Log Sequence Number):** monotonic ID of a log record; stamped on pages for idempotent redo.
- **Memtable:** in-memory sorted mutable write buffer (usually a skip list) in an LSM engine.
- **Merge operator (RocksDB):** registered function to apply deltas (e.g., counters) without read-modify-write.
- **MVCC:** Multi-Version Concurrency Control — readers see consistent snapshots while writers create new versions.
- **Page split / merge:** B-Tree node operations that maintain balance on insert/delete.
- **RUM conjecture:** you cannot minimize Read, Update, and Memory overhead simultaneously.
- **Sequence number (LSM):** monotonic version stamp; highest wins; enables snapshots.
- **Skip list:** probabilistic sorted structure with O(log n) ops; common memtable implementation.
- **Snapshot:** a consistent point-in-time view for MVCC reads.
- **SSTable (Sorted String Table):** immutable, sorted on-disk file of key→value pairs.
- **STCS (size-tiered compaction):** merges similarly sized SSTables; low WA, high SA/RA.
- **Tombstone:** deletion marker shadowing older values until compaction purges it.
- **TWCS (TimeWindowCompactionStrategy):** Cassandra strategy bucketing SSTables by time window; ideal for TTL time-series.
- **Universal compaction (RocksDB):** size-tiered-like, lower WA, higher transient space amp.
- **VACUUM (Postgres):** reclaims dead tuples (MVCC garbage) and prevents XID wraparound.
- **WAL (Write-Ahead Log) / redo log / commit log:** sequential durability log written before data changes.
- **Working set:** the subset of data actively accessed; must fit in RAM for good performance.
- **Write stall:** LSM throttling/stopping of writers when compaction can't keep up.
- **Zombie data:** deleted data resurrected because a tombstone was purged before all replicas saw it.

---

## 12. Cheat-sheet & self-test

### 12.1 One-screen recap

**The trade (memorize):** B-Tree = read-optimized, in-place, low/predictable RA, ~33% SA, latency-stable. LSM = write-optimized, append-only, low WA at write (sequential) but 10–30× via compaction, RA low *with Bloom filters*, SA ~10% (leveled) to ~2× (size-tiered), latency bimodal (stalls).

**Key numbers:**
- B-Tree height for billions of rows: **3–4** levels (fan-out 100s–1000s).
- Page sizes: Postgres **8 KB**, InnoDB **16 KB**, RocksDB block **4 KB**.
- RocksDB memtable default **64 MB**; L0 slowdown **20**, stop **36**; level multiplier **10**.
- Bloom: **~10 bits/key ≈ 1% FPP**; optimal FPP ≈ 0.6185^(m/n).
- B-Tree space slack ~**33%**; leveled SA ~**10%**; size-tiered transient SA ~**2×**.
- LSM total write amp (leveled): **~10–30×**.
- Cassandra `gc_grace_seconds` default **10 days**.

**Engines:** B-Tree → Postgres, InnoDB, Oracle, SQL Server, SQLite, LMDB. LSM → RocksDB, LevelDB, Cassandra, HBase, Scylla, Bigtable. Both → MyRocks-vs-InnoDB, WiredTiger.

**Decision rule:** read/scan-heavy + predictable latency + transactions → **B-Tree**. write/ingest-heavy + compression/scale + point reads → **LSM**. TTL time-series → **LSM + TWCS**.

**Compaction:** STCS (write-heavy, low WA/high SA), LCS (read/space-sensitive, low RA/SA/high WA), TWCS (TTL time-series), FIFO/Universal (specialized).

**Top failure signals:** B-Tree → small buffer pool, vacuum lag/bloat, random-key splits. LSM → compaction backlog/write stalls, tombstone pile-up/zombies, out-of-disk during big compaction.

**Top anti-patterns:** random UUIDv4 PK on B-Tree; undersized cache; long txn/snapshot blocking GC; range-scan workload on size-tiered LSM; deletes/TTL without TWCS; writing past sustainable LSM throughput.

### 12.2 Self-test (no answers — recall practice)

1. Trace an LSM point read for a key that was deleted 5 minutes ago but still has an old value in L3 — exactly which structures are consulted, in what order, and what is returned and why?
2. Your RocksDB instance just started returning second-long write latencies. List the metrics you'd check, in order, and the three most likely root causes with their fixes.
3. Derive why leveled compaction has ~10% space amplification but ~10–30× write amplification, while size-tiered is the reverse — reason from the level/tier structure, don't just recite.
4. A team proposes random UUIDv4 primary keys for a high-insert PostgreSQL table "for security." Explain the performance consequence at the page level and propose two alternatives that keep uniqueness.
5. Given a workload of 90% writes / 10% point reads / occasional large range scans, 50 TB growing, on NVMe SSDs with a tight storage budget — pick an engine and compaction strategy, justify each choice against the alternative, and name the single biggest operational risk and how you'd monitor it.
6. Explain how a deleted record can "come back from the dead" in Cassandra, the exact configuration that prevents it, and the analogous bloat mechanism in a B-Tree MVCC engine.
7. Why does a Bloom filter help LSM *negative* lookups the most, and why does it provide zero benefit to range scans? What would you tune if your bloom false-positive ratio (per `nodetool tablestats`) were 15%?
