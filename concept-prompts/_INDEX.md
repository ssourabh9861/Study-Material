# Index & Progress Tracker

**165 subtopic prompts** across **19 concept areas**. Tick each box as you generate its document.

How to generate one: open the folder's `PROMPT.md`, copy everything below `=== PROMPT STARTS HERE ===` into a fresh Claude chat, reply `continue` until done, and save the output as `<subtopic>.md` in that folder.

Generated: 2026-06-23

### 01. Distributed systems foundations

- [x] `01-distributed-systems-foundations/01-cap-and-pacelc` — Cap and pacelc
- [x] `01-distributed-systems-foundations/02-consistency-models` — Consistency models
- [x] `01-distributed-systems-foundations/03-consensus-raft-and-paxos` — Consensus raft and paxos
- [x] `01-distributed-systems-foundations/04-replication-and-quorums` — Replication and quorums
- [x] `01-distributed-systems-foundations/05-time-clocks-and-ordering` — Time clocks and ordering
- [x] `01-distributed-systems-foundations/06-failure-detection-and-membership` — Failure detection and membership

### 02. Distributed transactions and consistency

- [ ] `02-distributed-transactions-and-consistency/01-2pc-and-3pc` — 2pc and 3pc
- [ ] `02-distributed-transactions-and-consistency/02-saga-pattern` — Saga pattern
- [ ] `02-distributed-transactions-and-consistency/03-transactional-outbox-and-cdc` — Transactional outbox and cdc
- [ ] `02-distributed-transactions-and-consistency/04-idempotency-and-deduplication` — Idempotency and deduplication
- [ ] `02-distributed-transactions-and-consistency/05-delivery-semantics` — Delivery semantics
- [ ] `02-distributed-transactions-and-consistency/06-distributed-locks` — Distributed locks

### 03. Scalability and architecture patterns

- [x] `03-scalability-and-architecture-patterns/01-architectural-styles` — Architectural styles
- [x] `03-scalability-and-architecture-patterns/02-cqrs-and-event-sourcing` — Cqrs and event sourcing
- [x] `03-scalability-and-architecture-patterns/03-load-balancing-and-service-discovery` — Load balancing and service discovery
- [x] `03-scalability-and-architecture-patterns/04-rate-limiting` — Rate limiting
- [x] `03-scalability-and-architecture-patterns/05-backpressure-and-flow-control` — Backpressure and flow control
- [x] `03-scalability-and-architecture-patterns/06-gateway-bff-and-service-mesh` — Gateway bff and service mesh
- [x] `03-scalability-and-architecture-patterns/07-capacity-estimation` — Capacity estimation

### 04. Resilience and fault tolerance

- [x] `04-resilience-and-fault-tolerance/01-timeouts-retries-backoff` — Timeouts retries backoff
- [x] `04-resilience-and-fault-tolerance/02-circuit-breakers` — Circuit breakers
- [x] `04-resilience-and-fault-tolerance/03-bulkheads-and-isolation` — Bulkheads and isolation
- [x] `04-resilience-and-fault-tolerance/04-load-shedding-and-degradation` — Load shedding and degradation
- [x] `04-resilience-and-fault-tolerance/05-thundering-herd-and-stampede` — Thundering herd and stampede
- [x] `04-resilience-and-fault-tolerance/06-redundancy-failover-multiregion` — Redundancy failover multiregion
- [x] `04-resilience-and-fault-tolerance/07-chaos-engineering` — Chaos engineering

### 05. Databases paradigms and selection

- [x] `05-databases-paradigms-and-selection/01-acid-vs-base` — Acid vs base
- [x] `05-databases-paradigms-and-selection/02-relational-databases` — Relational databases
- [x] `05-databases-paradigms-and-selection/03-key-value-stores` — Key value stores
- [x] `05-databases-paradigms-and-selection/04-wide-column-stores` — Wide column stores
- [x] `05-databases-paradigms-and-selection/05-document-and-graph-databases` — Document and graph databases
- [x] `05-databases-paradigms-and-selection/06-storage-engines-btree-vs-lsm` — Storage engines btree vs lsm
- [x] `05-databases-paradigms-and-selection/07-isolation-levels-and-mvcc` — Isolation levels and mvcc
- [x] `05-databases-paradigms-and-selection/08-choosing-a-datastore` — Choosing a datastore

### 06. Database scaling and partitioning

- [ ] `06-database-scaling-and-partitioning/01-partitioning-and-sharding` — Partitioning and sharding
- [ ] `06-database-scaling-and-partitioning/02-consistent-hashing` — Consistent hashing
- [ ] `06-database-scaling-and-partitioning/03-replication-and-read-replicas` — Replication and read replicas
- [ ] `06-database-scaling-and-partitioning/04-replication-lag-handling` — Replication lag handling
- [ ] `06-database-scaling-and-partitioning/05-multiregion-and-geo-partitioning` — Multiregion and geo partitioning
- [ ] `06-database-scaling-and-partitioning/06-online-resharding-and-migration` — Online resharding and migration

### 07. Caching and in memory stores

- [ ] `07-caching-and-in-memory-stores/01-cache-patterns` — Cache patterns
- [ ] `07-caching-and-in-memory-stores/02-redis-deep-dive` — Redis deep dive
- [ ] `07-caching-and-in-memory-stores/03-memcached` — Memcached
- [ ] `07-caching-and-in-memory-stores/04-eviction-and-memory` — Eviction and memory
- [ ] `07-caching-and-in-memory-stores/05-cache-invalidation` — Cache invalidation
- [ ] `07-caching-and-in-memory-stores/06-stampede-protection` — Stampede protection
- [ ] `07-caching-and-in-memory-stores/07-cache-consistency-and-multilayer` — Cache consistency and multilayer

### 08. Kafka and message brokers

- [x] `08-kafka-and-message-brokers/01-architecture-and-the-log` — Architecture and the log
- [x] `08-kafka-and-message-brokers/02-replication-isr-controller-kraft` — Replication isr controller kraft
- [x] `08-kafka-and-message-brokers/03-producers-and-delivery` — Producers and delivery
- [x] `08-kafka-and-message-brokers/04-consumers-and-groups` — Consumers and groups
- [x] `08-kafka-and-message-brokers/05-rebalancing` — Rebalancing
- [x] `08-kafka-and-message-brokers/06-exactly-once-and-transactions` — Exactly once and transactions
- [x] `08-kafka-and-message-brokers/07-retention-and-log-compaction` — Retention and log compaction
- [x] `08-kafka-and-message-brokers/08-broker-comparison` — Broker comparison
- [x] `08-kafka-and-message-brokers/09-operations-and-troubleshooting` — Operations and troubleshooting

### 09. Java language and concurrency

- [x] `09-java-language-and-concurrency/01-type-system-and-generics` — Type system and generics
- [x] `09-java-language-and-concurrency/02-collections-framework-internals` — Collections framework internals
- [x] `09-java-language-and-concurrency/03-streams-and-functional` — Streams and functional
- [x] `09-java-language-and-concurrency/04-modern-java-features` — Modern java features
- [x] `09-java-language-and-concurrency/05-java-memory-model` — Java memory model
- [x] `09-java-language-and-concurrency/06-threads-and-context-switching` — Threads and context switching
- [x] `09-java-language-and-concurrency/07-synchronization-and-locks` — Synchronization and locks
- [x] `09-java-language-and-concurrency/08-atomics-and-cas` — Atomics and cas
- [x] `09-java-language-and-concurrency/09-executors-and-thread-pools` — Executors and thread pools
- [x] `09-java-language-and-concurrency/10-completablefuture-and-async` — Completablefuture and async
- [x] `09-java-language-and-concurrency/11-virtual-threads-and-structured-concurrency` — Virtual threads and structured concurrency
- [x] `09-java-language-and-concurrency/12-concurrent-collections` — Concurrent collections
- [x] `09-java-language-and-concurrency/13-concurrency-debugging` — Concurrency debugging

### 10. Jvm internals and gc

- [ ] `10-jvm-internals-and-gc/01-jvm-architecture-and-memory` — Jvm architecture and memory
- [ ] `10-jvm-internals-and-gc/02-jit-and-object-layout` — Jit and object layout
- [ ] `10-jvm-internals-and-gc/03-class-loading` — Class loading
- [ ] `10-jvm-internals-and-gc/04-gc-fundamentals` — Gc fundamentals
- [ ] `10-jvm-internals-and-gc/05-gc-algorithms` — Gc algorithms
- [ ] `10-jvm-internals-and-gc/06-gc-tuning-and-logs` — Gc tuning and logs
- [ ] `10-jvm-internals-and-gc/07-oom-and-leak-diagnosis` — Oom and leak diagnosis
- [ ] `10-jvm-internals-and-gc/08-profiling-and-low-latency-tuning` — Profiling and low latency tuning

### 11. Spring and hibernate

- [x] `11-spring-and-hibernate/01-ioc-and-dependency-injection` — Ioc and dependency injection
- [x] `11-spring-and-hibernate/02-bean-lifecycle-and-scopes` — Bean lifecycle and scopes
- [x] `11-spring-and-hibernate/03-aop` — Aop
- [x] `11-spring-and-hibernate/04-spring-boot-autoconfiguration` — Spring boot autoconfiguration
- [x] `11-spring-and-hibernate/05-spring-mvc-and-rest` — Spring mvc and rest
- [x] `11-spring-and-hibernate/06-spring-webflux-reactive` — Spring webflux reactive
- [x] `11-spring-and-hibernate/07-spring-data-jpa` — Spring data jpa
- [x] `11-spring-and-hibernate/08-hibernate-orm-internals` — Hibernate orm internals
- [x] `11-spring-and-hibernate/09-entity-mappings-and-relationships` — Entity mappings and relationships
- [x] `11-spring-and-hibernate/10-fetching-strategies-and-n+1` — Fetching strategies and n+1
- [x] `11-spring-and-hibernate/11-transactions-and-spring-tx` — Transactions and spring tx
- [x] `11-spring-and-hibernate/12-spring-security-and-testing` — Spring security and testing

### 12. Kubernetes and containers

- [ ] `12-kubernetes-and-containers/01-containers-and-linux-primitives` — Containers and linux primitives
- [ ] `12-kubernetes-and-containers/02-control-plane-architecture` — Control plane architecture
- [ ] `12-kubernetes-and-containers/03-workload-objects` — Workload objects
- [ ] `12-kubernetes-and-containers/04-scheduling-and-resources` — Scheduling and resources
- [ ] `12-kubernetes-and-containers/05-networking` — Networking
- [ ] `12-kubernetes-and-containers/06-storage` — Storage
- [ ] `12-kubernetes-and-containers/07-autoscaling` — Autoscaling
- [ ] `12-kubernetes-and-containers/08-config-and-secrets` — Config and secrets
- [ ] `12-kubernetes-and-containers/09-rollouts-and-probes` — Rollouts and probes
- [ ] `12-kubernetes-and-containers/10-troubleshooting` — Troubleshooting

### 13. Observability and sre

- [x] `13-observability-and-sre/01-three-pillars` — Three pillars
- [x] `13-observability-and-sre/02-metrics-and-prometheus` — Metrics and prometheus
- [x] `13-observability-and-sre/03-distributed-tracing` — Distributed tracing
- [x] `13-observability-and-sre/04-logging-and-aggregation` — Logging and aggregation
- [x] `13-observability-and-sre/05-slo-sli-error-budgets` — Slo sli error budgets
- [x] `13-observability-and-sre/06-alerting-design` — Alerting design
- [x] `13-observability-and-sre/07-grafana-and-dashboards` — Grafana and dashboards
- [x] `13-observability-and-sre/08-incident-response-and-postmortems` — Incident response and postmortems

### 14. Security for backend systems

- [x] `14-security-for-backend-systems/01-authn-vs-authz` — Authn vs authz
- [x] `14-security-for-backend-systems/02-oauth2-and-oidc` — Oauth2 and oidc
- [x] `14-security-for-backend-systems/03-jwt-deep-dive` — Jwt deep dive
- [x] `14-security-for-backend-systems/04-mtls-and-service-identity` — Mtls and service identity
- [x] `14-security-for-backend-systems/05-secrets-and-encryption` — Secrets and encryption
- [x] `14-security-for-backend-systems/06-owasp-top-10` — Owasp top 10
- [x] `14-security-for-backend-systems/07-api-security` — Api security
- [x] `14-security-for-backend-systems/08-request-integrity-and-replay` — Request integrity and replay
- [x] `14-security-for-backend-systems/09-data-privacy-and-pci` — Data privacy and pci

### 15. Api design and management

- [x] `15-api-design-and-management/01-rest-design` — Rest design
- [x] `15-api-design-and-management/02-grpc` — Grpc
- [x] `15-api-design-and-management/03-graphql` — Graphql
- [x] `15-api-design-and-management/04-versioning-and-compatibility` — Versioning and compatibility
- [x] `15-api-design-and-management/05-error-handling-and-idempotency` — Error handling and idempotency
- [x] `15-api-design-and-management/06-pagination-and-bulk` — Pagination and bulk
- [x] `15-api-design-and-management/07-webhooks` — Webhooks
- [x] `15-api-design-and-management/08-api-gateway-and-management` — Api gateway and management
- [x] `15-api-design-and-management/09-rate-limiting-and-quotas` — Rate limiting and quotas

### 16. Cicd and release engineering

- [x] `16-cicd-and-release-engineering/01-pipeline-fundamentals` — Pipeline fundamentals
- [x] `16-cicd-and-release-engineering/02-branching-and-feature-flags` — Branching and feature flags
- [x] `16-cicd-and-release-engineering/03-deployment-strategies` — Deployment strategies
- [x] `16-cicd-and-release-engineering/04-iac-terraform-helm` — Iac terraform helm
- [x] `16-cicd-and-release-engineering/05-gitops` — Gitops
- [x] `16-cicd-and-release-engineering/06-testing-in-ci` — Testing in ci
- [x] `16-cicd-and-release-engineering/07-supply-chain-security` — Supply chain security
- [x] `16-cicd-and-release-engineering/08-dora-metrics` — Dora metrics

### 17. Ai and llm foundations

- [ ] `17-ai-and-llm-foundations/01-llm-fundamentals` — Llm fundamentals
- [ ] `17-ai-and-llm-foundations/02-tokens-and-context-windows` — Tokens and context windows
- [ ] `17-ai-and-llm-foundations/03-embeddings-and-vector-search` — Embeddings and vector search
- [ ] `17-ai-and-llm-foundations/04-prompt-engineering` — Prompt engineering
- [ ] `17-ai-and-llm-foundations/05-function-and-tool-calling` — Function and tool calling
- [ ] `17-ai-and-llm-foundations/06-rag-architecture` — Rag architecture
- [ ] `17-ai-and-llm-foundations/07-chunking-and-retrieval` — Chunking and retrieval
- [ ] `17-ai-and-llm-foundations/08-reranking-and-evaluation` — Reranking and evaluation
- [ ] `17-ai-and-llm-foundations/09-fine-tuning-vs-rag` — Fine tuning vs rag
- [ ] `17-ai-and-llm-foundations/10-inference-and-serving` — Inference and serving
- [ ] `17-ai-and-llm-foundations/11-guardrails-and-observability` — Guardrails and observability

### 18. Mcp model context protocol

- [x] `18-mcp-model-context-protocol/01-overview-and-why-mcp` — Overview and why mcp
- [x] `18-mcp-model-context-protocol/02-architecture-host-client-server` — Architecture host client server
- [x] `18-mcp-model-context-protocol/03-transports` — Transports
- [x] `18-mcp-model-context-protocol/04-tools-primitive` — Tools primitive
- [x] `18-mcp-model-context-protocol/05-resources-primitive` — Resources primitive
- [x] `18-mcp-model-context-protocol/06-prompts-primitive` — Prompts primitive
- [x] `18-mcp-model-context-protocol/07-building-an-mcp-server` — Building an mcp server
- [x] `18-mcp-model-context-protocol/08-client-integration` — Client integration
- [x] `18-mcp-model-context-protocol/09-auth-and-security` — Auth and security
- [x] `18-mcp-model-context-protocol/10-sampling-and-roots` — Sampling and roots

### 19. Agentic ai and agents

- [x] `19-agentic-ai-and-agents/01-agent-fundamentals-and-loop` — Agent fundamentals and loop
- [x] `19-agentic-ai-and-agents/02-react-pattern` — React pattern
- [x] `19-agentic-ai-and-agents/03-planning-and-decomposition` — Planning and decomposition
- [x] `19-agentic-ai-and-agents/04-tool-use-and-orchestration` — Tool use and orchestration
- [x] `19-agentic-ai-and-agents/05-memory-and-state` — Memory and state
- [x] `19-agentic-ai-and-agents/06-multi-agent-systems` — Multi agent systems
- [x] `19-agentic-ai-and-agents/07-reflection-and-self-critique` — Reflection and self critique
- [x] `19-agentic-ai-and-agents/08-agent-frameworks` — Agent frameworks
- [x] `19-agentic-ai-and-agents/09-agent-evaluation` — Agent evaluation
- [x] `19-agentic-ai-and-agents/10-failure-modes-and-guardrails` — Failure modes and guardrails
- [x] `19-agentic-ai-and-agents/11-production-agent-architecture` — Production agent architecture
