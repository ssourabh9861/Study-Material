# MASTER PROMPT — Senior Engineer Handbook Generator

This file is the single source of truth for **how every document in this library is generated** and **what the library covers**. Two ways to use it:

- **As a reference:** it defines the author persona, the depth mandate, and the required document structure that every `*/PROMPT.md` in this repo specializes.
- **As a runnable prompt:** paste the block below `=== PROMPT STARTS HERE ===` into a fresh Claude chat to generate the **handbook roadmap** (how all the themes connect and the order to learn them). To generate a specific topic instead, open that subtopic folder's own `PROMPT.md`.

Audience throughout: a **senior software developer** (Java/JVM backend) building complete, first-principles-to-internals mastery of each topic — enough to design with it, run it in production, teach it, and answer any interview question on it.


=== PROMPT STARTS HERE ===

You are a **staff/principal-level software engineer and a meticulous technical author**. You are writing one definitive chapter of an engineering handbook. Produce an **exhaustive, self-contained reference-and-learning document** on the subtopic named at the bottom of this prompt.

**Reader profile:** a **senior software developer** working primarily in the Java/JVM backend ecosystem who wants to *fully master* this subtopic — from first principles all the way to deep internals — well enough to design with it, operate and debug it in production, teach it to others, and answer any interview question on it.

**Coverage mandate (this is the most important part — read carefully):**
- **Cover BOTH basics and advanced.** Begin from first principles: assume the reader is sharp but new to *this specific subtopic*. Then climb all the way to expert-level internals, edge cases, and tuning. Leave no rung of the ladder missing.
- **Be EXHAUSTIVE, not a summary.** There is effectively no length limit — long is expected and good. Never compress to save space. If you approach the response limit, stop at a clean point and end with exactly: `→ reply "continue"`, then resume seamlessly when asked.
- **Explain every referenced or adjacent concept inline, beginner-friendly.** The moment you mention something a newcomer might not know (e.g. ZooKeeper, a kernel scheduler, a syscall, Raft, MVCC, a CAP term), pause and explain it in 2–4 sentences before moving on. Never leave a name unexplained.
- **List internal workflows step by step** — what happens under the hood, in order: the control flow, the data flow, the lifecycle, the state transitions.
- **Enumerate the available toolkit** — the methods, classes, APIs, CLI commands, configuration flags, and tools relevant to this subtopic: what each does, its key parameters, and its defaults.
- **Cover implementation concerns** — performance, correctness/concurrency, memory, security, cost, observability, testability, production hardening, and the common anti-patterns.
- **Give MANY code examples across DIFFERENT use cases** — not one toy example. Make them idiomatic and complete enough to run or adapt, and explain the parts that matter. Default to **Java** where the topic is language-relevant; otherwise use the most appropriate language, CLI, or config.
- **Be concrete:** real defaults, real numbers, real tool names, real systems, real failure stories. Explicitly flag anything that is version- or vendor-specific.

## Required document structure (use these sections, in this order)
1. **Overview & where it fits** — what it is, the problem it solves, when you reach for it, and the one-paragraph mental model.
2. **Foundations from first principles** — the basics built up from zero; define every core term as you introduce it.
3. **How it works internally** — step-by-step internal workflow(s), lifecycle, data/control flow, state machine. This is the heart of the doc — go deep.
4. **The complete toolkit** — the methods / classes / APIs / commands / config available, each with purpose, key parameters, and defaults. Use tables.
5. **Code examples by use case** — several worked examples spanning different real scenarios (not variations of one). Idiomatic, explained, copy-adaptable.
6. **Implementation concerns & best practices** — performance, correctness, security, observability, cost, testing, production hardening, and anti-patterns to avoid.
7. **Advanced topics & deep internals** — expert-level material, edge cases, tuning knobs, lesser-known behavior.
8. **Tradeoffs & decision frameworks** — comparison tables, alternatives, explicit "use when… / avoid when…" rules.
9. **Failure modes & debugging** — what breaks in production, how to diagnose it (with the actual tools/commands), and real-world incidents.
10. **Interview drill** — 8–12 questions an interviewer is likely to ask, each with a crisp model answer **and** 2–3 deep-probe follow-ups (with answers). Include at least 3 "senior-signal" questions (tradeoff/justification, not recall).
11. **Glossary** — every referenced or adjacent term used anywhere in the doc, defined plainly enough for a beginner.
12. **Cheat-sheet & self-test** — a dense one-screen recap (key numbers, terms, decision rules), then **5+ self-test questions (no answers)** for active recall.

## The subtopic for THIS document (master roadmap mode)
**Produce:** a *handbook roadmap* — not a single topic. Map how the themes below interconnect, the dependencies between them (what to learn before what), a recommended study sequence for a senior backend engineer, and for each theme a 2–3 sentence statement of why it matters and what mastery looks like. Then give a 12-week and an accelerated 4-week study plan drawing on the themes. This is the "table of contents with a brain" for the whole library.

### Themes & curriculum covered by this library
- **01. Distributed systems foundations**
  - cap and pacelc
  - consistency models
  - consensus raft and paxos
  - replication and quorums
  - time clocks and ordering
  - failure detection and membership
- **02. Distributed transactions and consistency**
  - 2pc and 3pc
  - saga pattern
  - transactional outbox and cdc
  - idempotency and deduplication
  - delivery semantics
  - distributed locks
- **03. Scalability and architecture patterns**
  - architectural styles
  - cqrs and event sourcing
  - load balancing and service discovery
  - rate limiting
  - backpressure and flow control
  - gateway bff and service mesh
  - capacity estimation
- **04. Resilience and fault tolerance**
  - timeouts retries backoff
  - circuit breakers
  - bulkheads and isolation
  - load shedding and degradation
  - thundering herd and stampede
  - redundancy failover multiregion
  - chaos engineering
- **05. Databases paradigms and selection**
  - acid vs base
  - relational databases
  - key value stores
  - wide column stores
  - document and graph databases
  - storage engines btree vs lsm
  - isolation levels and mvcc
  - choosing a datastore
- **06. Database scaling and partitioning**
  - partitioning and sharding
  - consistent hashing
  - replication and read replicas
  - replication lag handling
  - multiregion and geo partitioning
  - online resharding and migration
- **07. Caching and in memory stores**
  - cache patterns
  - redis deep dive
  - memcached
  - eviction and memory
  - cache invalidation
  - stampede protection
  - cache consistency and multilayer
- **08. Kafka and message brokers**
  - architecture and the log
  - replication isr controller kraft
  - producers and delivery
  - consumers and groups
  - rebalancing
  - exactly once and transactions
  - retention and log compaction
  - broker comparison
  - operations and troubleshooting
- **09. Java language and concurrency**
  - type system and generics
  - collections framework internals
  - streams and functional
  - modern java features
  - java memory model
  - threads and context switching
  - synchronization and locks
  - atomics and cas
  - executors and thread pools
  - completablefuture and async
  - virtual threads and structured concurrency
  - concurrent collections
  - concurrency debugging
- **10. Jvm internals and gc**
  - jvm architecture and memory
  - jit and object layout
  - class loading
  - gc fundamentals
  - gc algorithms
  - gc tuning and logs
  - oom and leak diagnosis
  - profiling and low latency tuning
- **11. Spring and hibernate**
  - ioc and dependency injection
  - bean lifecycle and scopes
  - aop
  - spring boot autoconfiguration
  - spring mvc and rest
  - spring webflux reactive
  - spring data jpa
  - hibernate orm internals
  - entity mappings and relationships
  - fetching strategies and n+1
  - transactions and spring tx
  - spring security and testing
- **12. Kubernetes and containers**
  - containers and linux primitives
  - control plane architecture
  - workload objects
  - scheduling and resources
  - networking
  - storage
  - autoscaling
  - config and secrets
  - rollouts and probes
  - troubleshooting
- **13. Observability and sre**
  - three pillars
  - metrics and prometheus
  - distributed tracing
  - logging and aggregation
  - slo sli error budgets
  - alerting design
  - grafana and dashboards
  - incident response and postmortems
- **14. Security for backend systems**
  - authn vs authz
  - oauth2 and oidc
  - jwt deep dive
  - mtls and service identity
  - secrets and encryption
  - owasp top 10
  - api security
  - request integrity and replay
  - data privacy and pci
- **15. Api design and management**
  - rest design
  - grpc
  - graphql
  - versioning and compatibility
  - error handling and idempotency
  - pagination and bulk
  - webhooks
  - api gateway and management
  - rate limiting and quotas
- **16. Cicd and release engineering**
  - pipeline fundamentals
  - branching and feature flags
  - deployment strategies
  - iac terraform helm
  - gitops
  - testing in ci
  - supply chain security
  - dora metrics
- **17. Ai and llm foundations**
  - llm fundamentals
  - tokens and context windows
  - embeddings and vector search
  - prompt engineering
  - function and tool calling
  - rag architecture
  - chunking and retrieval
  - reranking and evaluation
  - fine tuning vs rag
  - inference and serving
  - guardrails and observability
- **18. Mcp model context protocol**
  - overview and why mcp
  - architecture host client server
  - transports
  - tools primitive
  - resources primitive
  - prompts primitive
  - building an mcp server
  - client integration
  - auth and security
  - sampling and roots
- **19. Agentic ai and agents**
  - agent fundamentals and loop
  - react pattern
  - planning and decomposition
  - tool use and orchestration
  - memory and state
  - multi agent systems
  - reflection and self critique
  - agent frameworks
  - agent evaluation
  - failure modes and guardrails
  - production agent architecture

## Constraints
- Technically precise over vague. If you are unsure of a number/default, say so rather than inventing it.
- Use tables for any comparison of 3+ options.
- Tight, information-dense prose — no filler, no marketing language.
- Prefer correctness and completeness over brevity. This is a reference doc.
- Keep code blocks correct and runnable/adaptable; comment the non-obvious lines.

Begin now. Remember: be exhaustive, explain every adjacent term for a beginner, and when you run low on space end with exactly `→ reply "continue"` and resume seamlessly on request.
