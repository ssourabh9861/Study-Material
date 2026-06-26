# Spring Security & Testing

> A definitive engineering-handbook chapter for senior JVM backend developers. We start from first principles, climb to deep internals, and end with interview drills, a glossary, and a self-test. Defaults and behavior are stated for **Spring Boot 3.x / Spring Security 6.x / Spring Framework 6.x** unless explicitly flagged otherwise. Where 5.x/legacy behavior differs materially, it is called out.

---

## 1. Overview & where it fits

**Spring Security** is the de-facto authentication-and-authorization framework for Spring applications. It answers two distinct questions for every request that hits your application:

1. **Authentication (AuthN)** — *Who are you?* Verify the caller's identity (username/password, a token, a certificate, an API key).
2. **Authorization (AuthZ)** — *What are you allowed to do?* Decide whether the now-known identity may perform the requested action on the requested resource.

It also handles a long tail of cross-cutting concerns you would otherwise hand-roll badly: **CSRF protection**, **CORS**, **password hashing**, **session fixation protection**, **security headers** (HSTS, X-Content-Type-Options, etc.), **HTTP Basic / form login / OAuth2 / OIDC / SAML**, **method-level security**, and **secured-endpoint testing support**.

**The problem it solves.** Security is a *cross-cutting concern*: it touches every endpoint, must be applied uniformly, and is catastrophic if applied inconsistently. Hand-rolling it means every controller re-implements the same checks, drifts over time, and leaks. Spring Security centralizes this into a **declarative, filter-based pipeline** that intercepts requests *before* they reach your controllers, so your business code stays clean and the policy lives in one place.

> **Adjacent term — "cross-cutting concern":** a behavior (logging, security, transactions) that applies across many modules rather than living in one. Aspect-oriented and filter-based designs exist to factor these out so they aren't copy-pasted everywhere.

**When you reach for it.** Any time a Spring/Spring Boot app must restrict access: a REST API behind tokens, a server-rendered web app with login forms, a microservice validating JWTs minted by an identity provider, or an internal service needing mTLS. If you have *any* notion of "logged-in user" or "this endpoint is admin-only," you use Spring Security.

**One-paragraph mental model.** A request enters a chain of **servlet filters** (the `SecurityFilterChain`). Early filters try to *establish* an identity — they read a session cookie, a `Basic` header, or a `Bearer` JWT and produce an `Authentication` object, which they store in a thread-local `SecurityContext`. Later filters *enforce* policy — the `AuthorizationFilter` checks the established identity against the rules you declared (`requestMatchers(...).hasRole(...)`). If authentication is missing or authorization fails, an `ExceptionTranslationFilter` converts the resulting exception into either a "go log in" response (a 302 redirect or a 401 challenge) or a 403. If everything passes, the request reaches your `DispatcherServlet` and controller, where **method security** (`@PreAuthorize`) can apply a second, finer layer of checks. **Testing** is the other half of this chapter: Spring Boot gives you a *test pyramid* — fast sliced tests (`@WebMvcTest`, `@DataJpaTest`), full-context integration tests (`@SpringBootTest`), `MockMvc` for driving the web layer without a network, `@MockBean`/`@MockitoBean` for stubbing collaborators, Testcontainers for real databases/brokers in CI, and `spring-security-test` (`@WithMockUser`, `SecurityMockMvcRequestPostProcessors`) for exercising secured endpoints.

---

## 2. Foundations from first principles

We build the vocabulary from zero. If you already know servlets and filters, skim — but the precise definitions matter later.

### 2.1 Servlets, filters, and the chain

> **Servlet:** the Java standard (Jakarta Servlet, formerly `javax.servlet`, now `jakarta.servlet` in Spring 6) for handling HTTP on the JVM. A servlet container (Tomcat, Jetty, Undertow) receives a TCP connection, parses HTTP into an `HttpServletRequest`, and routes it to a `Servlet`. In Spring MVC there is essentially **one** servlet, the `DispatcherServlet`, which then routes to your `@Controller` methods.

> **Filter:** a `jakarta.servlet.Filter` is a component that wraps request processing. Each filter receives the request, may inspect/modify/short-circuit it, and then calls `chain.doFilter(req, res)` to pass control to the next filter (or finally the servlet). Filters run **before** the servlet and can also run code on the way back out (after `doFilter` returns). This "Russian-doll" wrapping is the **Chain of Responsibility** pattern.

> **Chain of Responsibility (design pattern):** a sequence of handlers, each of which can either handle a request or pass it along. Spring Security's filter chain is exactly this — each security filter does one job and delegates onward.

Spring Security plugs into the servlet world via a **single** registered filter, `DelegatingFilterProxy`, which delegates to a Spring-managed bean named `springSecurityFilterChain`. That bean is a `FilterChainProxy`, which holds one or more `SecurityFilterChain` instances. Each `SecurityFilterChain` is *a request matcher plus an ordered list of security filters*. This indirection lets Spring Security's filters be real Spring beans (with dependency injection, lifecycle, etc.) even though the servlet container only knows about plain filters.

```
Servlet container filters:
  [ ... app filters ... ] -> DelegatingFilterProxy("springSecurityFilterChain")
                                        |
                                        v
                              FilterChainProxy
                                        |
                  picks the FIRST SecurityFilterChain whose matcher matches
                                        |
                                        v
            [ SecurityContextHolderFilter ] -> [ CsrfFilter ] -> ... ->
            [ AuthorizationFilter ] -> [ DispatcherServlet -> controller ]
```

### 2.2 Principal, credentials, authorities

> **Principal:** the entity making the request — usually a user, sometimes a service account. After authentication, the principal is typically a `UserDetails` object or a JWT-derived object.

> **Credentials:** the secret used to prove identity — a password, a token, a private key. Spring Security tries hard to erase credentials from memory once authentication succeeds (`eraseCredentials`).

> **Authority / GrantedAuthority:** a permission string attached to a principal, e.g. `ROLE_ADMIN` or `SCOPE_read`. A **role** is just an authority with a conventional `ROLE_` prefix. Authorization rules are expressed in terms of authorities.

> **Role vs authority — the prefix trap:** `hasRole("ADMIN")` checks for the authority `ROLE_ADMIN` (it auto-prepends `ROLE_`). `hasAuthority("ROLE_ADMIN")` checks the literal string. Mixing them is a classic bug: if your `UserDetailsService` returns authority `ADMIN` (no prefix) and you call `hasRole("ADMIN")`, it silently fails because the framework looks for `ROLE_ADMIN`.

### 2.3 The core domain objects

| Object | What it is | Lifetime |
|---|---|---|
| `Authentication` | Holds the principal, credentials, authorities, and an `authenticated` boolean. Before AuthN it's a "request" token (e.g. `UsernamePasswordAuthenticationToken` with `authenticated=false`); after, it's an "authenticated" token. | Per request |
| `SecurityContext` | A thin holder with a single `Authentication`. | Per request (bound to thread) |
| `SecurityContextHolder` | A static accessor that stores the `SecurityContext`, by default in a `ThreadLocal`. `SecurityContextHolder.getContext().getAuthentication()` is how any code reads "who is the current user." | Thread-scoped |
| `UserDetails` | The framework's representation of a user record: username, (hashed) password, authorities, account flags (enabled, locked, expired). | Loaded on demand |
| `UserDetailsService` | A SAM interface: `UserDetails loadUserByUsername(String)`. Your bridge to the user store (DB, LDAP, in-memory). | Singleton bean |
| `AuthenticationManager` | The entry point that *performs* authentication: `Authentication authenticate(Authentication)`. | Singleton bean |
| `AuthenticationProvider` | A pluggable strategy the manager delegates to (e.g. `DaoAuthenticationProvider` for username/password against a `UserDetailsService`). | Singleton bean |
| `PasswordEncoder` | Hashes and verifies passwords (`encode`, `matches`). | Singleton bean |

> **SAM interface (Single Abstract Method):** a functional interface with exactly one method, so it can be implemented with a lambda. `UserDetailsService` is a SAM, which is why you often see `username -> ...` lambdas.

> **ThreadLocal:** a variable whose value is private to each thread. Spring Security stores the current `SecurityContext` here so any code on the same thread can read "the current user" without passing it around. The catch: it does **not** automatically propagate to *other* threads (async tasks, thread pools) — see §7 on `DelegatingSecurityContextExecutor`.

### 2.4 Authentication vs authorization, restated precisely

- **Authentication filters** read incoming evidence and call the `AuthenticationManager`. On success they put an authenticated `Authentication` into the `SecurityContext`. Examples: `UsernamePasswordAuthenticationFilter` (form login), `BasicAuthenticationFilter` (HTTP Basic), `BearerTokenAuthenticationFilter` (OAuth2 resource server JWT).
- **Authorization** happens in `AuthorizationFilter` (Spring Security 6) — it consults an `AuthorizationManager` that evaluates your `authorizeHttpRequests` rules against the `Authentication` in context. (In Spring Security 5 the equivalent was `FilterSecurityInterceptor` + `AccessDecisionManager`/voters — now deprecated.)

### 2.5 Stateful (session) vs stateless (token)

> **HTTP is stateless:** each request is independent; the server doesn't inherently remember prior requests. To have a "logged-in" concept you either (a) keep a **session** server-side and hand the client a session-id cookie, or (b) make every request **self-describing** by carrying a signed token.

- **Stateful / session-based:** after login, the server stores a `SecurityContext` in the `HttpSession` and the client gets a `JSESSIONID` cookie. Subsequent requests present the cookie; the `SecurityContextHolderFilter` (formerly `SecurityContextPersistenceFilter`) reloads the context from the session. Good for server-rendered apps; requires sticky sessions or a shared session store when horizontally scaled.
- **Stateless / token-based:** no server session. Every request carries a `Bearer` token (a JWT or an opaque token). The server validates the token's signature/expiry on each request and rebuilds the `Authentication` from its claims. Scales horizontally with zero shared state. The default for modern REST APIs.

> **JWT (JSON Web Token):** a compact, URL-safe token of three Base64URL parts — `header.payload.signature`. The payload ("claims") carries `sub` (subject/user), `exp` (expiry), `iss` (issuer), `scope`/`roles`, etc. The signature (HMAC with a shared secret, or RSA/EC with a private key) lets the server verify the token wasn't tampered with **without** a database lookup. Anyone can *read* a JWT (it's not encrypted, just signed) — never put secrets in it.

> **OAuth2:** an authorization *framework* where a client obtains an **access token** from an **authorization server** and presents it to a **resource server** (your API). Spring Security's "resource server" support validates those tokens. **OIDC (OpenID Connect)** layers authentication on top of OAuth2, adding an **ID token** (a JWT describing the user).

> **CSRF (Cross-Site Request Forgery):** an attack where a malicious site tricks a logged-in user's browser into making an unwanted state-changing request to your app, riding on the user's cookies. Defense: require an unguessable token on state-changing requests that the attacker's site can't read. **Only relevant when the browser auto-attaches credentials (cookies)** — pure token APIs (no cookies) are not CSRF-vulnerable, which is why Spring Security lets you disable CSRF for stateless APIs.

> **CORS (Cross-Origin Resource Sharing):** a browser security mechanism. By the **same-origin policy**, a page at `https://app.com` can't read responses from `https://api.other.com` unless that server explicitly opts in via `Access-Control-Allow-Origin` headers. CORS is *not* a server-side authorization mechanism; it constrains what *browsers* allow JS to read. Spring Security has to be CORS-aware so its filters don't reject the browser's **preflight** `OPTIONS` request.

---

## 3. How it works internally

This is the heart of the chapter. We trace the full lifecycle of a request, then the authentication and authorization sub-flows in detail.

### 3.1 Boot-time wiring (how the chain gets built)

1. Spring Boot's `SecurityAutoConfiguration` + `SecurityFilterAutoConfiguration` run. If you define a `SecurityFilterChain` bean, Boot backs off its defaults and uses yours; if you define none, Boot creates a default chain that secures everything with HTTP Basic + form login and generates a random password (logged at startup).
2. Your `@Configuration` class returns one or more `SecurityFilterChain` beans, built via the `HttpSecurity` DSL.
3. A `FilterChainProxy` is assembled holding those chains **in order**. The order is critical: `FilterChainProxy` picks the **first** chain whose request matcher matches and runs *only that chain*.
4. A `DelegatingFilterProxy` named `springSecurityFilterChain` is registered with the servlet container at a default order (`SecurityProperties.DEFAULT_FILTER_ORDER`, which is `OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER - 100`, effectively early).

### 3.2 The ordered filter list (default chain, abbreviated)

Within a `SecurityFilterChain`, filters run in a fixed canonical order. Key ones, in order:

| # | Filter | Job |
|---|---|---|
| 1 | `DisableEncodeUrlFilter` | Prevents session id being encoded into URLs (a leak). |
| 2 | `WebAsyncManagerIntegrationFilter` | Propagates `SecurityContext` to Spring MVC async (`Callable`) processing. |
| 3 | `SecurityContextHolderFilter` | Loads the `SecurityContext` from the repository (e.g. session) at request start; clears the `ThreadLocal` at request end. (Replaced `SecurityContextPersistenceFilter` in SS6 — it no longer *saves* automatically; saving is explicit.) |
| 4 | `HeaderWriterFilter` | Writes security response headers (X-Content-Type-Options, X-Frame-Options, HSTS, etc.). |
| 5 | `CorsFilter` | Handles CORS preflight and adds CORS headers (if configured). |
| 6 | `CsrfFilter` | Validates the CSRF token on state-changing requests. |
| 7 | `LogoutFilter` | Handles `/logout`. |
| 8 | `UsernamePasswordAuthenticationFilter` | Processes form-login POST (if form login enabled). |
| 9 | `DefaultLoginPageGeneratingFilter` / `DefaultLogoutPageGeneratingFilter` | Auto-generates login/logout HTML pages. |
| 10 | `BasicAuthenticationFilter` | Processes the `Authorization: Basic` header. |
| 11 | `BearerTokenAuthenticationFilter` | (Resource server) processes `Authorization: Bearer` JWT/opaque tokens. |
| 12 | `RequestCacheAwareFilter` | Restores a saved request after login (so you land where you were going). |
| 13 | `SecurityContextHolderAwareRequestFilter` | Wraps the request so servlet APIs like `isUserInRole()` work. |
| 14 | `AnonymousAuthenticationFilter` | If no auth was established, sets an *anonymous* `Authentication` (principal `anonymousUser`, authority `ROLE_ANONYMOUS`) so downstream code never sees `null`. |
| 15 | `SessionManagementFilter` | (When configured) session fixation protection, concurrency control. |
| 16 | `ExceptionTranslationFilter` | Wraps the rest of the chain in try/catch; converts `AuthenticationException` → entry point (401/redirect) and `AccessDeniedException` → 403. |
| 17 | `AuthorizationFilter` | **The enforcement point.** Evaluates `authorizeHttpRequests` rules. Throws `AccessDeniedException` (caught above) if denied. |

> **Why `ExceptionTranslationFilter` sits just *before* `AuthorizationFilter`:** authorization runs *inside* the try/catch, so when `AuthorizationFilter` throws, the translation filter catches it and produces the right HTTP response. This is the mechanism that turns "you're denied" into either "go authenticate" (401/302) or "forbidden" (403).

### 3.3 End-to-end request lifecycle (form-login, stateful)

1. **Request arrives.** `DelegatingFilterProxy` → `FilterChainProxy` selects the matching chain.
2. **`SecurityContextHolderFilter`** asks the `SecurityContextRepository` (default `HttpSessionSecurityContextRepository`, but lazily) for a stored context. If a `JSESSIONID` maps to a session containing a `SecurityContext`, that `Authentication` is set into the `ThreadLocal`.
3. **`CsrfFilter`** — for `POST/PUT/DELETE/PATCH`, it loads the expected CSRF token (default repository: `HttpSessionCsrfTokenRepository`) and compares it to the token in the request header/param. Mismatch → 403 via the chain's access-denied handling.
4. **Authentication filters** — if this is a login POST to `/login`, `UsernamePasswordAuthenticationFilter` extracts username/password, builds an unauthenticated `UsernamePasswordAuthenticationToken`, and calls `AuthenticationManager.authenticate(...)` (see §3.4). On success it (a) stores the authenticated context, (b) by default migrates the session id (fixation protection), (c) redirects to the saved request or default success URL.
5. **`AnonymousAuthenticationFilter`** — if nothing authenticated, set anonymous identity.
6. **`AuthorizationFilter`** — evaluate rules. For `requestMatchers("/admin/**").hasRole("ADMIN")`, it checks `ROLE_ADMIN` ∈ authorities. If denied → `AccessDeniedException`.
7. **`ExceptionTranslationFilter`** catches:
   - `AuthenticationException` (or anonymous user hitting a protected URL) → invoke `AuthenticationEntryPoint` (form login → 302 to `/login`; Basic → `401 WWW-Authenticate: Basic`).
   - `AccessDeniedException` for an *authenticated* user → 403 via `AccessDeniedHandler`.
8. **Pass-through.** If authorized, control reaches `DispatcherServlet` → controller. **Method security** interceptors (if enabled) wrap the bean method and may throw `AccessDeniedException` (handled at the AOP layer, surfaced as 403).
9. **Unwind.** On the way back, `SecurityContextHolderFilter`'s `finally` clears the `ThreadLocal` (preventing context bleed across pooled threads). In SS6 you must *explicitly* save the context after programmatic authentication (`securityContextRepository.saveContext(...)`), because automatic save-on-every-request was removed for performance.

### 3.4 Authentication sub-flow (DaoAuthenticationProvider)

```
UsernamePasswordAuthenticationToken(unauthenticated)
        |
        v
AuthenticationManager (ProviderManager)
        |  iterates providers; first that "supports(...)" handles it
        v
DaoAuthenticationProvider
   1. userDetails = userDetailsService.loadUserByUsername(username)
        - throws UsernameNotFoundException if absent (often masked as BadCredentials)
   2. additionalAuthenticationChecks:
        - passwordEncoder.matches(rawPassword, userDetails.getPassword())
        - on mismatch -> BadCredentialsException
   3. preAuth/postAuth checks: account enabled? locked? expired? credentials expired?
   4. build authenticated UsernamePasswordAuthenticationToken(principal, null, authorities)
        - credentials erased
        v
SecurityContextHolder.getContext().setAuthentication(result)
```

> **`ProviderManager`:** the default `AuthenticationManager`. It holds a list of `AuthenticationProvider`s and tries each until one supports the token type and returns a result (or all fail → throw the last `AuthenticationException`). It can also delegate to a *parent* `AuthenticationManager` (used to build hierarchies, e.g. global vs local).

> **`AuthenticationException` masking:** by default `DaoAuthenticationProvider.hideUserNotFoundExceptions = true`, so a missing user is reported as `BadCredentials` — deliberately, to avoid leaking which usernames exist (a **user-enumeration** defense).

### 3.5 Authentication sub-flow (JWT resource server, stateless)

```
Request: Authorization: Bearer eyJ...
        v
BearerTokenAuthenticationFilter
   - BearerTokenResolver extracts the token from the header
        v
AuthenticationManager -> JwtAuthenticationProvider
   1. JwtDecoder.decode(token)
        - verifies signature using a key from the JWK Set URI / issuer / shared secret
        - validates exp, nbf, iss, aud via OAuth2TokenValidator
   2. JwtAuthenticationConverter maps claims -> GrantedAuthorities
        - default: "scope"/"scp" claim -> SCOPE_* authorities
   3. build JwtAuthenticationToken(jwt, authorities)
        v
SecurityContext set (NOT persisted to a session by default; stateless)
```

> **JWK Set (JSON Web Key Set):** a JSON document, usually at `/.well-known/jwks.json`, listing the issuer's public keys (with `kid` key-ids). The resource server fetches it (and caches it, default ~5 min) to verify RS256/ES256 signatures without holding any secret. Configure with `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` or `issuer-uri` (which auto-discovers the JWKS via OIDC metadata at `/.well-known/openid-configuration`).

> **Opaque tokens:** tokens that aren't self-describing JWTs. The resource server validates them by calling the authorization server's **introspection endpoint** (RFC 7662) on every request — a network hop, but supports instant revocation. Configure with `spring.security.oauth2.resourceserver.opaquetoken.*`.

### 3.6 Authorization internals (Spring Security 6)

In SS6, authorization is unified under `AuthorizationManager<T>` with a single method `AuthorizationDecision check(Supplier<Authentication>, T object)`.

- `AuthorizationFilter` uses a `RequestMatcherDelegatingAuthorizationManager` built from your `authorizeHttpRequests` DSL. It maps each request matcher to an `AuthorizationManager` (`hasRole`, `hasAuthority`, `authenticated`, `permitAll`, `access(customManager)`).
- Method security uses `AuthorizationManager`s wired by `AuthorizationManagerBeforeMethodInterceptor` (for `@PreAuthorize`) and `...AfterMethodInterceptor` (for `@PostAuthorize`).

> **Why this replaced voters:** SS5 used `AccessDecisionManager` + `AccessDecisionVoter` (affirmative/consensus/unanimous strategies). It was flexible but verbose and hard to reason about. SS6's `AuthorizationManager` is a clean functional interface, composable via `AuthorizationManagers.allOf/anyOf`. The voter API is deprecated/removed in SS6.

### 3.7 SecurityContext propagation and persistence (the SS6 change)

In SS5, `SecurityContextPersistenceFilter` automatically saved the context to the session at the end of every request. SS6 split this:

- `SecurityContextHolderFilter` **loads** (deferred/lazy) and **clears** but does **not** save.
- Saving is now explicit and happens at well-defined points (after the framework's own login filters succeed). If *you* authenticate programmatically and want it remembered across requests, you must call `securityContextRepository.saveContext(context, request, response)` yourself.

> **Why:** the old behavior re-serialized the context to the session on *every* request even when nothing changed, and could spuriously create sessions for stateless flows. The split makes statelessness honest and saves work.

---

## 4. The complete toolkit

### 4.1 The `HttpSecurity` DSL (most-used methods)

Configured inside a `SecurityFilterChain` bean. SS6 uses **lambda DSL** exclusively (the chained `.and()` style is removed).

| Method | Purpose | Notable defaults |
|---|---|---|
| `securityMatcher(...)` | Restricts this chain to certain requests (multi-chain setups). | If absent, chain matches all. |
| `authorizeHttpRequests(c -> ...)` | Declare URL authorization rules. | Replaces deprecated `authorizeRequests`. |
| `formLogin(c -> ...)` | Enable form-based login. | Default login page `/login`, POST to `/login`, params `username`/`password`. |
| `httpBasic(c -> ...)` | Enable HTTP Basic. | Realm "Realm". |
| `oauth2ResourceServer(c -> c.jwt(...))` | Validate incoming Bearer JWTs/opaque tokens. | — |
| `oauth2Login(c -> ...)` | Act as an OAuth2/OIDC client (login via Google etc.). | Redirect-based. |
| `logout(c -> ...)` | Configure logout. | `/logout`, invalidates session, clears cookies. |
| `csrf(c -> ...)` | Configure/disable CSRF. | **Enabled by default**; token in session. |
| `cors(c -> ...)` | Enable CORS using a `CorsConfigurationSource` bean. | Off unless a source bean exists or configured. |
| `sessionManagement(c -> ...)` | Session creation policy, fixation, concurrency. | Creation policy `IF_REQUIRED`. |
| `headers(c -> ...)` | Security response headers. | Sensible secure defaults on. |
| `exceptionHandling(c -> ...)` | Custom entry point / access-denied handler. | Depends on auth mechanisms present. |
| `addFilterBefore/After/At(...)` | Insert a custom filter. | — |
| `requestCache(c -> ...)` | Configure saved-request behavior. | `HttpSessionRequestCache`. |
| `rememberMe(c -> ...)` | Persistent "remember me" cookie login. | Token-hash based. |
| `anonymous(c -> ...)` | Configure/disable anonymous auth. | Enabled. |
| `x509(...)` | mTLS client-cert auth. | — |

#### `authorizeHttpRequests` rule vocabulary

| Rule | Meaning |
|---|---|
| `permitAll()` | Anyone, including anonymous. |
| `denyAll()` | No one. |
| `authenticated()` | Any non-anonymous authenticated user. |
| `anonymous()` | Only anonymous (not logged in). |
| `hasRole("X")` | Authority `ROLE_X`. |
| `hasAnyRole("X","Y")` | Any of `ROLE_X`, `ROLE_Y`. |
| `hasAuthority("X")` | Literal authority `X`. |
| `hasAnyAuthority(...)` | Any of the literals. |
| `access(AuthorizationManager)` | Custom programmatic decision. |
| `requestMatchers(...)` | Scope the rule to paths/methods. |

> **Ordering matters:** rules are evaluated top-to-bottom; the **first** matching `requestMatchers` wins. Put specific rules before broad ones, and end with `anyRequest().authenticated()` (or `denyAll()`) to be safe-by-default. A misplaced `anyRequest().permitAll()` near the top silently opens everything below it.

### 4.2 Method security annotations

Enable with `@EnableMethodSecurity` (SS6; replaces `@EnableGlobalMethodSecurity`). It turns on `@PreAuthorize`/`@PostAuthorize` by default; enable JSR-250 (`@RolesAllowed`) and legacy `@Secured` explicitly.

| Annotation | When it runs | Notes |
|---|---|---|
| `@PreAuthorize("expr")` | **Before** method invocation | Most common; full SpEL, can reference args (`#id`) and `authentication`. |
| `@PostAuthorize("expr")` | **After**, can inspect `returnObject` | E.g. `returnObject.owner == authentication.name`. Method *runs*, then result may be denied. |
| `@PreFilter` / `@PostFilter` | Filter elements of a collection arg/result | Expensive on large collections. |
| `@Secured("ROLE_X")` | Before | Simple, no SpEL. Off by default in SS6. |
| `@RolesAllowed("X")` (JSR-250) | Before | Standard annotation; enable with `@EnableMethodSecurity(jsr250Enabled = true)`. |

> **SpEL (Spring Expression Language):** a runtime expression language. In `@PreAuthorize` it gives you `hasRole(...)`, `hasAuthority(...)`, `principal`, `authentication`, method arguments by name (`#userId`), and custom bean refs (`@myService.canEdit(#id)`). It's powerful but be wary: complex SpEL is hard to test and can hide injection risk if you interpolate untrusted input.

> **Method security mechanics:** it's **Spring AOP** (proxy-based). Therefore (a) it only applies when the method is called *through the proxy* — self-invocation within the same bean bypasses it; (b) the target must be a Spring bean; (c) by default proxies are interface-based (JDK dynamic proxies) or CGLIB subclass proxies. `@PreAuthorize` on a `private`/`final` method won't be intercepted.

### 4.3 Password encoding

| Encoder | Algorithm | Default cost | Notes |
|---|---|---|---|
| `BCryptPasswordEncoder` | bcrypt | strength 10 (2^10 rounds) | The workhorse; salt embedded in hash. |
| `Argon2PasswordEncoder` | Argon2id | memory/iterations/parallelism configurable | Modern, memory-hard; strongest choice. Needs BouncyCastle. |
| `SCryptPasswordEncoder` | scrypt | — | Memory-hard alternative. |
| `Pbkdf2PasswordEncoder` | PBKDF2 | iterations ~ 310000 (varies by version) | FIPS-friendly. |
| `DelegatingPasswordEncoder` | multi | — | **The default** from `PasswordEncoderFactories.createDelegatingPasswordEncoder()`. |

> **`DelegatingPasswordEncoder` / the `{id}` prefix:** stored hashes carry a prefix like `{bcrypt}$2a$10$...` or `{argon2}$argon2id$...`. On `matches`, the delegating encoder reads the prefix and routes to the right algorithm. This lets you **migrate hashing schemes without a flag day** — old `{bcrypt}` hashes keep verifying while new passwords are written with `{argon2}`. Never use `NoOpPasswordEncoder` (plaintext) outside throwaway demos.

### 4.4 Testing toolkit (annotations & helpers)

| Tool | What it does | Context loaded |
|---|---|---|
| `@SpringBootTest` | Full application context. Optionally a real/random port server. | Everything |
| `@WebMvcTest(Foo.class)` | Slice: MVC layer only — controllers, `@ControllerAdvice`, filters, `MockMvc` auto-configured. | Web tier; no `@Service`/`@Repository` beans |
| `@DataJpaTest` | Slice: JPA layer — repositories, `EntityManager`, embedded DB, transactional+rollback. | Persistence tier |
| `@JsonTest` | Slice: Jackson/Gson serialization. | JSON marshaling beans |
| `@RestClientTest` | Slice: `RestTemplate`/`RestClient`/`WebClient` with `MockRestServiceServer`. | HTTP client beans |
| `@MockitoBean` (SS Boot 3.4+) / `@MockBean` (deprecated) | Replace a bean in the context with a Mockito mock. | — |
| `@MockitoSpyBean` / `@SpyBean` | Wrap a real bean in a spy. | — |
| `MockMvc` | Drives the dispatcher servlet *in-process* (no socket). | — |
| `WebTestClient` | Reactive/non-blocking test client; works against MockMvc or a live server. | — |
| `TestRestTemplate` | Blocking HTTP client for `@SpringBootTest(webEnvironment=RANDOM_PORT)` real-server tests. | — |
| `@WithMockUser` | Inject a fake authenticated user into the `SecurityContext` for the test. | — |
| `@WithUserDetails` | Like above but loads via your real `UserDetailsService`. | — |
| `@WithAnonymousUser` | Run as anonymous. | — |
| `@WithSecurityContext` | Build a custom context (custom annotations). | — |
| `SecurityMockMvcRequestPostProcessors` | `user(...)`, `jwt(...)`, `httpBasic(...)`, `csrf()`, `oauth2Login()` request post-processors. | — |
| `@Testcontainers` + `@Container` | Spin real Docker dependencies (Postgres, Kafka) for a test. | — |
| `@ServiceConnection` (Boot 3.1+) | Auto-wires Spring datasource/Kafka props from a Testcontainer. | — |
| `@DynamicPropertySource` | Inject container host/port into Spring `Environment`. | — |

### 4.5 Key configuration properties (Spring Boot)

| Property | Meaning |
|---|---|
| `spring.security.user.name` / `.password` / `.roles` | The single default in-memory user (when no `UserDetailsService` bean). |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | OIDC issuer; auto-discovers JWKS + validates `iss`. |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | Direct JWKS URL. |
| `spring.security.oauth2.resourceserver.jwt.public-key-location` | Static RSA public key (single-key setups). |
| `spring.security.oauth2.resourceserver.opaquetoken.introspection-uri` | Opaque-token introspection endpoint. |
| `spring.security.oauth2.client.registration.*` | OAuth2/OIDC login client registrations (Google, Okta, etc.). |
| `server.servlet.session.timeout` | Session idle timeout (default 30m). |
| `server.servlet.session.cookie.same-site` | `Lax` (default in Boot) / `Strict` / `None`. |

---

## 5. Code examples by use case

All examples are Spring Boot 3.x / Spring Security 6.x, `jakarta.*` imports.

### 5.1 Stateful form-login web app with DB-backed users

```java
@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

    // The chain: form login, CSRF on, session-based.
    @Bean
    SecurityFilterChain web(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/css/**", "/js/**", "/register").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")   // ROLE_ADMIN required
                .anyRequest().authenticated())                    // safe default
            .formLogin(form -> form
                .loginPage("/login")            // custom page (must be permitAll above? add it)
                .defaultSuccessUrl("/dashboard", true)
                .permitAll())
            .logout(logout -> logout
                .logoutSuccessUrl("/?logout")
                .deleteCookies("JSESSIONID"))
            // CSRF stays ENABLED (default) for a cookie/session app — do not disable it.
            .sessionManagement(s -> s
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(f -> f.changeSessionId())); // default; prevents fixation
        return http.build();
    }

    // Bridge to the DB. JdbcUserDetailsManager or a custom UserDetailsService.
    @Bean
    UserDetailsService userDetailsService(UserRepository repo) {
        return username -> repo.findByUsername(username)
            .map(u -> User.withUsername(u.getUsername())
                .password(u.getPasswordHash())   // already-encoded, with {bcrypt} prefix
                .authorities(u.getRoles().stream()
                    .map(r -> "ROLE_" + r).toArray(String[]::new))
                .disabled(!u.isEnabled())
                .build())
            .orElseThrow(() -> new UsernameNotFoundException(username));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Delegating: reads {id} prefix; writes new hashes as bcrypt by default.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
```

Registering a user (note: encode once, store the prefixed hash):

```java
@Service
public class RegistrationService {
    private final UserRepository repo;
    private final PasswordEncoder encoder;
    // ... constructor ...

    public void register(String username, String rawPassword) {
        String hash = encoder.encode(rawPassword); // -> "{bcrypt}$2a$10$..."
        repo.save(new UserEntity(username, hash, Set.of("USER"), true));
    }
}
```

> **Login-page gotcha:** if you set a custom `loginPage("/login")` you must also `permitAll` it (and the POST target), or unauthenticated users get redirected to a page they can't see — an infinite redirect loop. The auto-generated login page (no `loginPage(...)`) avoids this.

### 5.2 Stateless REST API as an OAuth2 JWT resource server

```java
@Configuration
@EnableWebSecurity
public class ApiSecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**")  // this chain only governs /api/**
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasAuthority("SCOPE_admin")
                .anyRequest().authenticated())
            // Stateless: no session, so no CSRF needed (no cookies in play).
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable) // safe ONLY because we are token-only, no cookies
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter())));
        return http.build();
    }

    // Map a custom "roles" claim into ROLE_* authorities, in addition to scopes.
    @Bean
    JwtAuthenticationConverter jwtAuthConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter(); // SCOPE_*
        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities = new ArrayList<>(scopes.convert(jwt));
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }
            return authorities;
        });
        return conv;
    }
}
```

`application.yml`:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://login.example.com/   # auto-discovers JWKS, validates iss/exp
```

> **Why CSRF off here is correct:** the API authenticates purely from the `Authorization: Bearer` header, which a browser does **not** attach automatically across origins. With no ambient credential (cookie), there's nothing for CSRF to forge. If you ever switch to a cookie-stored token, CSRF protection becomes mandatory again.

### 5.3 Self-issued JWT (own auth server, HMAC) + login endpoint

For when you mint your own tokens (a single service, symmetric key).

```java
@Configuration
public class JwtConfig {
    // Symmetric secret (HS256). Keep it >= 256 bits; externalize via env/secret manager.
    @Bean
    JwtEncoder jwtEncoder(@Value("${app.jwt.secret}") String secret) {
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret) {
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }
}

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthenticationManager authManager;
    private final JwtEncoder encoder;
    // constructor ...

    @PostMapping("/login")
    public TokenResponse login(@RequestBody LoginRequest req) {
        // Authenticate username/password through the normal manager/provider.
        Authentication auth = authManager.authenticate(
            new UsernamePasswordAuthenticationToken(req.username(), req.password()));

        Instant now = Instant.now();
        String scope = auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).collect(Collectors.joining(" "));
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("self")
            .issuedAt(now)
            .expiresAt(now.plus(15, ChronoUnit.MINUTES)) // short-lived access token
            .subject(auth.getName())
            .claim("scope", scope)
            .build();
        String token = encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new TokenResponse(token, 900);
    }
}
```

Expose the `AuthenticationManager` as a bean:

```java
@Bean
AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
    return cfg.getAuthenticationManager();
}
```

> **Key sizing for HS256:** the secret must be at least 256 bits (32 bytes) or Nimbus throws. RS256 (asymmetric) is preferable across services so verifiers only need the public key. Keep access tokens short (5–15 min) and pair with longer-lived, revocable refresh tokens.

### 5.4 Method security with `@PreAuthorize`/`@PostAuthorize`

```java
@Configuration
@EnableMethodSecurity   // turns on @PreAuthorize/@PostAuthorize (jsr250/secured opt-in)
public class MethodSecurityConfig {}

@Service
public class DocumentService {

    @PreAuthorize("hasRole('EDITOR')")
    public void publish(Long docId) { /* ... */ }

    // Argument-aware: only the owner or an admin may read.
    @PostAuthorize("returnObject.ownerId == authentication.name or hasRole('ADMIN')")
    public Document load(Long docId) { /* ... */ return repo.findById(docId).orElseThrow(); }

    // Delegate to a bean for complex logic (keeps SpEL readable & testable).
    @PreAuthorize("@docPermissions.canEdit(#docId, authentication.name)")
    public void update(Long docId, DocumentPatch patch) { /* ... */ }

    // Filter a returned collection to elements the user owns.
    @PostFilter("filterObject.ownerId == authentication.name")
    public List<Document> myDocuments() { /* ... */ return repo.findAll(); }
}
```

> **`@PostAuthorize` runs the method first.** If `load` has side effects or is expensive, you've paid that cost before the deny. Use it only when the decision genuinely needs the return value. For ownership checks prefer pushing the filter into the query (`findByIdAndOwner`).

### 5.5 CORS configured correctly (browser SPA + JWT API)

```java
@Bean
CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration c = new CorsConfiguration();
    c.setAllowedOrigins(List.of("https://app.example.com")); // explicit, never "*" with credentials
    c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    c.setAllowCredentials(false); // true only if you send cookies; then origins must be explicit
    c.setMaxAge(Duration.ofMinutes(30)); // cache preflight
    UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
    src.registerCorsConfiguration("/api/**", c);
    return src;
}
// In the chain: .cors(Customizer.withDefaults())  // picks up the bean above
```

> **The `*` + credentials trap:** the CORS spec forbids `Access-Control-Allow-Origin: *` together with `Access-Control-Allow-Credentials: true`. Browsers reject it. If you need credentials, you must echo a specific origin. Also: enabling `cors()` in the chain ensures the preflight `OPTIONS` is permitted *before* authorization runs, otherwise your auth rules 401 the preflight and the real request never happens.

### 5.6 Custom filter (API-key gateway) inserted into the chain

```java
public class ApiKeyFilter extends OncePerRequestFilter {
    private final String expectedKey;
    public ApiKeyFilter(String expectedKey) { this.expectedKey = expectedKey; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader("X-API-Key");
        if (key != null && MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8), expectedKey.getBytes(StandardCharsets.UTF_8))) {
            var auth = new PreAuthenticatedAuthenticationToken(
                "api-client", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(req, res); // continue regardless; AuthorizationFilter enforces
    }
}
// .addFilterBefore(new ApiKeyFilter(key), UsernamePasswordAuthenticationFilter.class)
```

> **`OncePerRequestFilter`:** a base class guaranteeing the filter runs at most once per request even across forwards/includes. Use **constant-time comparison** (`MessageDigest.isEqual`) for secrets to avoid timing side-channels.

### 5.7 Test: `@WebMvcTest` slice over a secured controller

```java
@WebMvcTest(controllers = AccountController.class) // loads ONLY the web slice + security
class AccountControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AccountService accountService; // collaborator stubbed (not a real bean here)

    @Test
    @WithMockUser(roles = "USER")               // fake authenticated user, authority ROLE_USER
    void getOwnAccount_returns200() throws Exception {
        given(accountService.findFor("alice")).willReturn(new AccountDto("alice", 42));
        mockMvc.perform(get("/accounts/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(42));
    }

    @Test
    void anonymous_is401or302() throws Exception {
        mockMvc.perform(get("/accounts/me"))
            .andExpect(status().isUnauthorized()); // or 3xx if form login configured
    }

    @Test
    @WithMockUser(roles = "USER")
    void post_withoutCsrf_is403() throws Exception {
        mockMvc.perform(post("/accounts/transfer").contentType(APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden()); // CSRF token missing
    }

    @Test
    @WithMockUser(roles = "USER")
    void post_withCsrf_ok() throws Exception {
        mockMvc.perform(post("/accounts/transfer")
                .with(csrf())                     // adds a valid CSRF token
                .contentType(APPLICATION_JSON).content("{\"to\":\"bob\",\"amount\":5}"))
            .andExpect(status().isOk());
    }
}
```

> **`@WebMvcTest` and security:** by default it **does** auto-configure Spring Security with your `SecurityFilterChain` (it imports security config). So your auth rules are active in the slice — that's why CSRF and `@WithMockUser` behave realistically. If your security config pulls in beans not present in the slice (e.g. a `UserDetailsService` that needs a repo), provide a `@MockitoBean` or a `@TestConfiguration`.

### 5.8 Test: JWT resource-server endpoint with `jwt()` post-processor

```java
@WebMvcTest(ReportController.class)
class ReportControllerSecurityTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean ReportService reports;

    @Test
    void readWithScope_ok() throws Exception {
        mockMvc.perform(get("/api/reports/7")
                .with(jwt().jwt(j -> j.subject("svc").claim("scope", "reports.read"))
                          .authorities(new SimpleGrantedAuthority("SCOPE_reports.read"))))
            .andExpect(status().isOk());
    }

    @Test
    void readWithoutScope_forbidden() throws Exception {
        mockMvc.perform(get("/api/reports/7").with(jwt())) // no scope authority
            .andExpect(status().isForbidden());
    }
}
```

> The `jwt()` post-processor **bypasses the real decoder** — it injects a ready-made `JwtAuthenticationToken` so you don't need a live issuer/JWKS in unit tests. To test the *decoder/issuer wiring itself*, use a full `@SpringBootTest` with a mock OAuth2 server (e.g. `MockWebServer` serving a JWKS, or `spring-security-oauth2` test helpers).

### 5.9 Test: `@DataJpaTest` repository slice

```java
@DataJpaTest // embedded DB by default; transactional, rolls back each test
class AccountRepositoryTest {
    @Autowired AccountRepository repo;
    @Autowired TestEntityManager em; // helper for arranging persisted state

    @Test
    void findByOwner_returnsMatch() {
        em.persistAndFlush(new AccountEntity("alice", 100));
        var found = repo.findByOwner("alice");
        assertThat(found).isPresent().get().extracting(AccountEntity::getBalance).isEqualTo(100L);
    }
}
```

> **`@DataJpaTest` defaults:** replaces your DataSource with an embedded DB (H2) — fast but H2 SQL dialect differs from Postgres/MySQL, so dialect-specific queries can pass here and fail in prod. To test against the real engine, add `@AutoConfigureTestDatabase(replace = NONE)` and point at a Testcontainer (next example).

### 5.10 Test: Testcontainers + real Postgres

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // keep real DataSource
@Testcontainers
class AccountRepositoryPgTest {

    @Container
    @ServiceConnection // Boot 3.1+: auto-wires spring.datasource.* from this container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired AccountRepository repo;

    @Test
    void worksAgainstRealPostgres() {
        repo.save(new AccountEntity("bob", 5));
        assertThat(repo.findByOwner("bob")).isPresent();
    }
}
```

> **Testcontainers:** a library that programmatically starts throwaway Docker containers for tests and tears them down afterward. `@ServiceConnection` removed the old boilerplate of `@DynamicPropertySource` for supported services (JDBC, Kafka, Redis, Mongo, etc.). `static` container = one container shared across all methods in the class (faster). Requires a Docker daemon in CI.

### 5.11 Test: full `@SpringBootTest` integration with a live server

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransferIntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate rest; // real HTTP over the wire to the random port

    @Test
    void unauthenticatedIsRejected() {
        var resp = rest.getForEntity("/api/accounts/me", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void withBasicAuth() {
        var resp = rest.withBasicAuth("alice", "password")
            .getForEntity("/api/accounts/me", String.class);
        assertThat(resp.getStatusCode()).is2xxSuccessful();
    }
}
```

### 5.12 Test: `@WithUserDetails` against the real `UserDetailsService`

```java
@SpringBootTest
@AutoConfigureMockMvc
class AdminFlowTest {
    @Autowired MockMvc mockMvc;

    @Test
    @WithUserDetails(value = "admin", userDetailsServiceBeanName = "userDetailsService")
    void adminCanAccess() throws Exception {
        mockMvc.perform(get("/admin/users")).andExpect(status().isOk());
    }
}
```

> `@WithUserDetails` exercises your *actual* `UserDetailsService`, so authorities come from real wiring (a more faithful test than `@WithMockUser`, which fabricates them). Requires the user to exist in whatever store the service reads (seed it, or back it with a Testcontainer + `@Sql`).

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Stateless beats stateful at scale.** Session affinity/replication is overhead and a failure surface. Prefer short-lived JWTs verified locally via cached JWKS.
- **JWKS caching.** The resource server caches keys (default ~5 min). A misconfigured low TTL or a flaky JWKS endpoint adds latency and outages to *every* request. Monitor JWKS fetch latency.
- **bcrypt cost.** Strength 10 ≈ a few ms per hash; each +1 doubles cost. Tune so a single hash is ~50–250 ms on your hardware — slow enough to resist brute force, fast enough not to DoS your login endpoint. Login is a natural rate-limit/throttle point.
- **`@PostFilter`/`@PreFilter` on large collections** iterate every element through SpEL — O(n) reflection. Push filtering into queries.
- **Method security is AOP** — proxy overhead is negligible per call but self-invocation silently bypasses it (a correctness, not perf, footgun).

### 6.2 Correctness & concurrency

- **`ThreadLocal` doesn't cross threads.** `@Async`, `CompletableFuture`, manual thread pools, and reactive `Scheduler`s lose the `SecurityContext`. Use `DelegatingSecurityContextExecutor`/`DelegatingSecurityContextExecutorService`, or set `SecurityContextHolder.setStrategyName(MODE_INHERITABLETHREADLOCAL)` (inherits to child threads — but pooled threads reuse, so be careful), or in WebFlux use the reactive `ReactiveSecurityContextHolder` (Reactor Context, not ThreadLocal).
- **SS6 explicit save.** After programmatic `authManager.authenticate(...)` in a stateful app, you must persist the context to the repository or it won't survive the next request.
- **Rule ordering.** First match wins in `authorizeHttpRequests`; an early `permitAll` opens everything below it.

### 6.3 Security hardening checklist

- **Keep CSRF on for any cookie/session app.** Only disable for genuinely stateless, non-cookie token APIs.
- **Never `NoOpPasswordEncoder`.** Use the delegating encoder; prefer bcrypt/argon2.
- **`anyRequest().authenticated()` / `denyAll()` as the catch-all** so a new endpoint is locked by default (fail-closed).
- **Validate JWT `iss`, `aud`, `exp`, `nbf`.** Use `issuer-uri` (validates `iss` automatically) and add an audience validator. Reject `alg: none`.
- **Short token lifetimes + refresh tokens.** JWTs can't be revoked mid-life; keep them short. For instant revocation, use opaque tokens + introspection or a deny-list.
- **Security headers:** keep HSTS, `X-Content-Type-Options: nosniff`, frame options on. Set `SameSite=Lax/Strict` on session cookies.
- **Don't leak user existence:** keep `hideUserNotFoundExceptions`; return generic "bad credentials."
- **Constant-time secret comparison** for API keys.
- **Protect actuator:** `management.endpoints` can expose `/actuator/env`, `/heapdump`. Secure them; never `permitAll` them blindly.
- **CORS:** explicit origins, no `*` with credentials.

### 6.4 Observability

- Publish/consume Spring's `AuthenticationSuccessEvent`, `AbstractAuthenticationFailureEvent`, `AuthorizationDeniedEvent` (`@EventListener`) to audit logins and denials.
- Log the `SecurityFilterChain` at DEBUG (`logging.level.org.springframework.security=DEBUG`) to see which filters run and where a request is rejected — invaluable when "it returns 403 and I don't know why."
- Micrometer doesn't auto-time security by default; add counters around login success/failure and JWKS fetch.

### 6.5 Testing strategy (the pyramid in Spring)

> **The test pyramid:** many fast **unit** tests at the base, fewer **integration/slice** tests in the middle, very few slow **end-to-end** tests at the top. Spring Boot maps onto this cleanly.

| Layer | Tool | Speed | Use for |
|---|---|---|---|
| Unit | Plain JUnit + Mockito, no Spring context | µs–ms | Pure logic, services with mocked deps. |
| Slice | `@WebMvcTest`, `@DataJpaTest`, `@JsonTest`, `@RestClientTest` | ms (partial context) | One tier in isolation: controller+security, repo+DB, serialization. |
| Integration | `@SpringBootTest` (MOCK or RANDOM_PORT) + Testcontainers | hundreds ms–s | Cross-tier wiring, real DB/broker behavior. |
| E2E | Full stack, real network, sometimes external | s+ | Smoke/critical-path only. |

- **Prefer slices** for most coverage — they're fast and load only what you need.
- **`@MockitoBean` (was `@MockBean`)** replaces beans in the context; **plain `@Mock`** is for non-Spring unit tests. Don't reach for `@SpringBootTest` when a slice will do — context startup dominates suite time. Spring caches contexts across tests with identical configuration, so **minimize the number of distinct context configurations** to maximize cache hits.
- **Test security explicitly:** assert that anonymous gets 401/302, wrong-role gets 403, missing CSRF gets 403, right-role gets 200. Security regressions are silent otherwise.

### 6.6 Common anti-patterns

- Disabling CSRF "to make POSTs work" in a cookie app (opens CSRF holes).
- `permitAll()` too high in the rule list.
- `hasRole("ROLE_ADMIN")` (double prefix → never matches) or `hasAuthority("ADMIN")` when you meant a role.
- Encoding the password twice (encode at registration *and* again somewhere).
- `@PreAuthorize` on self-invoked methods (AOP bypass).
- `@SpringBootTest` for everything → 20-minute test suites.
- H2-only `@DataJpaTest` then surprised by Postgres-specific failures in prod.
- Storing JWTs in `localStorage` accessible to XSS, or in non-`HttpOnly` cookies.

---

## 7. Advanced topics & deep internals

### 7.1 Multiple `SecurityFilterChain`s

You can register several chains; `FilterChainProxy` tries them **in `@Order`** and runs the **first** whose `securityMatcher` matches. Classic pattern: one chain for `/api/**` (stateless JWT) and another for everything else (form login). Pitfall: a too-broad matcher on an early chain swallows requests meant for a later chain. Always `@Order` them and make matchers disjoint.

### 7.2 The deferred/lazy `SecurityContext` (SS6)

`SecurityContextHolderFilter` loads the context **lazily** via a `Supplier` — the session is only read if something actually calls `SecurityContextHolder.getContext()`. This avoids creating/reading sessions for endpoints that don't need identity. It also means a `@PreAuthorize` deep in the call stack triggers the load on first access.

### 7.3 Reactive (WebFlux) security

In WebFlux there are **no ThreadLocals** (one event-loop thread serves many requests). Security state lives in the **Reactor `Context`** via `ReactiveSecurityContextHolder`. The equivalents: `SecurityWebFilterChain` (not `SecurityFilterChain`), `ServerHttpSecurity` DSL, `ReactiveAuthenticationManager`, `ReactiveUserDetailsService`, `@EnableWebFluxSecurity`. `@PreAuthorize` works but the method must return a reactive type. Don't call blocking `SecurityContextHolder` from reactive code.

### 7.4 OAuth2 client vs resource server vs authorization server

- **Resource server:** *validates* incoming tokens (`oauth2ResourceServer`). Most APIs.
- **Client:** *obtains* tokens / logs users in via an external IdP (`oauth2Login`, `oauth2Client`). For BFF/web apps.
- **Authorization server:** *issues* tokens. Spring Security doesn't do this; use **Spring Authorization Server** (a separate project) or Keycloak/Auth0/Okta.

### 7.5 Custom `AuthorizationManager` and composition

```java
AuthorizationManager<RequestAuthorizationContext> ownAccount =
    (authSupplier, ctx) -> {
        String pathUser = ctx.getVariables().get("user");
        boolean ok = authSupplier.get().getName().equals(pathUser);
        return new AuthorizationDecision(ok);
    };
// .requestMatchers("/users/{user}/**").access(ownAccount)
// compose: AuthorizationManagers.allOf(a, b) / anyOf(a, b)
```

### 7.6 CSRF token internals & SPAs

Default `CsrfTokenRepository` stores the token in the `HttpSession`. For SPAs, use `CookieCsrfTokenRepository.withHttpOnlyFalse()` so JS can read the `XSRF-TOKEN` cookie and echo it as the `X-XSRF-TOKEN` header (the "double-submit cookie" pattern). SS6 changed CSRF token *deferred loading* and BREACH-protection encoding — if you upgraded from SS5 and SPAs broke, you likely need `CsrfTokenRequestAttributeHandler` configured to opt out of the new "deferred" behavior. (Flag: this is SS6.0+ specific.)

### 7.7 Session management deep dive

- `SessionCreationPolicy`: `ALWAYS`, `IF_REQUIRED` (default), `NEVER` (don't create, use if exists), `STATELESS` (never create or use).
- **Session fixation protection** (`changeSessionId` default): on login, the session id is changed so a pre-login id an attacker planted is invalidated.
- **Concurrent session control**: `maximumSessions(1)` to prevent multiple simultaneous logins; requires an `HttpSessionEventPublisher` bean.

### 7.8 Run-as and authority elevation

`@PreAuthorize` with run-as (`RunAsManager`) can temporarily grant an authority for a downstream call (rare; mostly for service-to-service elevation). SS6 retains `runAs(...)` support via method security.

### 7.9 Testing internals: how `@WithMockUser` works

`@WithMockUser` is meta-annotated with `@WithSecurityContext(factory = ...)`. A `WithSecurityContextTestExecutionListener` runs **before** the test method, calls the factory to build a `SecurityContext`, and sets it in the holder; it clears it after. `SecurityMockMvcRequestPostProcessors.user(...)` instead attaches the context to that *single request* via a `RequestPostProcessor` — useful when one test exercises multiple identities.

### 7.10 Context caching in the test runner

The Spring `TestContext` framework caches `ApplicationContext`s keyed by configuration (annotations, properties, active profiles, mock bean definitions). Tests with **identical** keys reuse the context (huge speedup). `@MockitoBean`/`@DirtiesContext`/different `@TestPropertySource` create new keys → new contexts → slower suites. This is the single biggest lever on Spring test-suite speed.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Stateful session vs stateless token

| Dimension | Session (stateful) | JWT (stateless) | Opaque token + introspection |
|---|---|---|---|
| Horizontal scale | Needs shared/sticky sessions | Trivial | Needs introspection call |
| Revocation | Instant (drop session) | Hard (wait for expiry) | Instant |
| Per-request cost | Session lookup | Local signature verify | Network introspection |
| CSRF exposure | Yes (cookies) | No (header token) | No |
| Payload size | Small cookie | Larger token each request | Small token |
| Best for | Server-rendered apps | Microservice APIs, SPAs | APIs needing revocation |

**Use session when:** classic server-rendered web app, single node or easy shared store, need instant logout.
**Use JWT when:** stateless REST/microservices, SPA/mobile clients, you control short lifetimes.
**Use opaque/introspection when:** you need revocation *and* central control and can afford the round trip (cache it).

### 8.2 Password encoders

| | bcrypt | argon2id | scrypt | pbkdf2 |
|---|---|---|---|---|
| Memory-hard | No | Yes | Yes | No |
| Tuning knobs | cost | mem/iter/parallelism | N/r/p | iterations |
| FIPS-acceptable | No | No | No | Yes |
| Default pick | Good baseline | **Best** if deps allow | Alt | When FIPS required |

### 8.3 Test types

**Use `@WebMvcTest` when:** testing controllers/validation/security rules/serialization without DB or services.
**Use `@DataJpaTest` when:** testing repository queries/mappings.
**Use `@SpringBootTest` when:** verifying cross-tier wiring or a full request path; pair with Testcontainers for real infra.
**Avoid `@SpringBootTest` when:** a slice suffices — context startup is the cost.
**Use Testcontainers when:** DB/broker-specific behavior matters or you've been burned by H2/real-DB divergence.

### 8.4 Method security vs URL security

- **URL (`authorizeHttpRequests`)**: coarse, perimeter, fast, declared in one place. Use as the primary gate.
- **Method (`@PreAuthorize`)**: fine-grained, data-aware (`#id`, `returnObject`), lives next to the logic. Use for domain rules ("only the owner"). Use **both** (defense in depth) — perimeter rules at the edge, method rules at the service.

---

## 9. Failure modes & debugging

### 9.1 "Everything returns 403"
- **CSRF** on a cookie app and the client isn't sending the token → 403 on POST/PUT/DELETE. Check `CsrfFilter` at DEBUG; add token or (for token APIs only) disable CSRF.
- **Role/authority mismatch** — `hasRole("ADMIN")` but authorities are `ADMIN` not `ROLE_ADMIN`, or vice versa.
- Diagnose: `logging.level.org.springframework.security=TRACE` prints the matched rule and the deny.

### 9.2 "Everything returns 401 / infinite redirect to /login"
- Custom `loginPage` not `permitAll`ed → redirect loop.
- Missing/expired/wrong-issuer JWT on a resource server → 401. Decode the token at jwt.io; check `iss`/`exp`/`aud`; verify JWKS reachable.

### 9.3 "Works in test, 403 in prod (or vice versa)"
- `@WebMvcTest` may not load a security bean that prod needs (or `@WithMockUser` fabricated an authority your real `UserDetailsService` never grants). Re-test with `@WithUserDetails`/`@SpringBootTest`.

### 9.4 "CORS errors in browser console"
- Preflight `OPTIONS` is being 401'd because `cors()` isn't enabled in the chain *or* the matcher excludes it. Symptom: "No 'Access-Control-Allow-Origin' header." Check `Access-Control-Allow-Origin` echoes the origin; ensure `cors()` is in the chain so OPTIONS is permitted before authz.

### 9.5 "Security context lost in async/thread pool"
- `@Async` method sees anonymous/null user. Fix with `DelegatingSecurityContextExecutor` or configure `SecurityContextHolder` strategy. In WebFlux, you used a blocking holder instead of `ReactiveSecurityContextHolder`.

### 9.6 "Login succeeds but next request is unauthenticated" (SS6 upgrade)
- You authenticated programmatically and didn't **save** the context to the repository (SS6 no longer auto-saves). Call `securityContextRepository.saveContext(...)` and ensure `requireExplicitSave` semantics are met.

### 9.7 "Method security ignored"
- Self-invocation (calling the `@PreAuthorize` method from within the same bean) bypasses the AOP proxy. Move the method to another bean or inject self-proxy. Also check `@EnableMethodSecurity` is present and the method is `public`/non-final.

### 9.8 Slow test suite
- Too many distinct contexts. Consolidate `@MockitoBean` usage, share base test configs, avoid `@DirtiesContext`. Reuse static Testcontainers across classes.

### 9.9 Real-world incident shape
A common production story: a team disabled CSRF globally to fix a broken AJAX POST in a session-cookie app. Months later, a CSRF attack drained funds via a forged transfer form on a malicious page — the browser auto-attached the session cookie and the server had no token to validate. **Lesson:** never disable CSRF in a cookie app; fix the client to send the token (double-submit cookie for SPAs).

---

## 10. Interview drill

**Q1. Walk me through what happens to an HTTP request inside Spring Security, filter by filter.**
*Model answer:* `DelegatingFilterProxy` → `FilterChainProxy` selects the matching `SecurityFilterChain`. `SecurityContextHolderFilter` loads any stored context; `CsrfFilter` validates state-changing requests; authentication filters (Basic/form/Bearer) establish identity via the `AuthenticationManager`; `AnonymousAuthenticationFilter` sets anonymous if none; `ExceptionTranslationFilter` wraps the tail in try/catch; `AuthorizationFilter` enforces `authorizeHttpRequests` rules. Denials become 401/redirect (auth) or 403 (authz) via the translation filter; success reaches the `DispatcherServlet`.
- *Follow-up: Why is `ExceptionTranslationFilter` placed just before `AuthorizationFilter`?* So authorization runs inside its try/catch and thrown `AccessDeniedException`/`AuthenticationException` can be translated to the right response.
- *Follow-up: What changed in SS6 about saving the context?* `SecurityContextHolderFilter` no longer auto-saves; saving is explicit, avoiding needless session writes.
- *Follow-up: How does `FilterChainProxy` choose among multiple chains?* First chain (by order) whose `securityMatcher` matches; only that one runs.

**Q2. Authentication vs authorization in Spring Security terms — name the objects.**
*Model answer:* AuthN = establishing the `Authentication` (via `AuthenticationManager`/`Provider`/`UserDetailsService`) and storing it in the `SecurityContext`. AuthZ = `AuthorizationFilter`/`AuthorizationManager` evaluating rules against that `Authentication`.
- *Follow-up: Role vs authority?* A role is an authority with `ROLE_` prefix; `hasRole("X")` checks `ROLE_X`.
- *Follow-up: Where do authorities come from for a JWT?* From claims via a `JwtAuthenticationConverter` (default: `scope`→`SCOPE_*`).

**Q3. Stateless JWT vs session — when do you choose which, and what about CSRF?**
*Model answer:* (See §8.1.) Session for server-rendered/instant-revocation; JWT for scalable APIs. CSRF only matters when credentials are ambient (cookies); pure header-token APIs aren't CSRF-vulnerable, so disabling CSRF there is correct.
- *Follow-up (senior signal): How do you revoke a JWT?* You largely can't mid-life — keep lifetimes short, use refresh tokens, or maintain a deny-list / switch to opaque + introspection.
- *Follow-up: Where do you store the token on a browser client?* Prefer an `HttpOnly`, `Secure`, `SameSite` cookie (then you need CSRF) over `localStorage` (XSS-exposed).

**Q4. How does `@PreAuthorize` work and what are its limits?**
*Model answer:* Spring AOP proxy intercepts the method before invocation, evaluates SpEL via an `AuthorizationManager`, throws `AccessDeniedException` on deny.
- *Follow-up: Why might it not fire?* Self-invocation bypasses the proxy; method must be public/non-final; bean must be Spring-managed.
- *Follow-up: `@PreAuthorize` vs `@PostAuthorize` cost?* `@PostAuthorize` runs the method first, then checks the return value — pay the side effects/cost regardless.

**Q5. Explain `DelegatingPasswordEncoder` and password migration.**
*Model answer:* It reads a `{id}` prefix on the stored hash and routes to the matching encoder, enabling mixed-scheme stores and gradual migration without a flag day.
- *Follow-up: How to upgrade from bcrypt to argon2?* New writes use argon2 (set as default delegate); old `{bcrypt}` hashes still verify; optionally re-encode on successful login (`upgradeEncoding`).
- *Follow-up: bcrypt cost tuning?* Aim ~50–250 ms/hash on prod hardware; balance brute-force resistance vs login-endpoint DoS.

**Q6. (Senior signal) Design auth for a 30-service microservice platform.**
*Model answer:* Central IdP (OIDC). Each service is a resource server validating JWTs locally via cached JWKS (`issuer-uri`), stateless, no shared session. Use a BFF or API gateway for browser clients (gateway holds the session/refresh token, forwards short-lived access tokens). Validate `iss`/`aud`; per-service scopes/roles in claims; method security for data-level rules. Short access tokens + refresh; opaque tokens or a revocation deny-list where instant revocation is required. Mutual TLS for east-west service calls.
- *Follow-up: How do you propagate identity across service hops?* Forward the bearer token (or exchange via OAuth2 token exchange); never trust caller-supplied user headers.
- *Follow-up: JWKS endpoint outage blast radius?* Every verify can fail; mitigate with longer key cache + stale-while-revalidate, and key pre-fetch.

**Q7. How do you test a secured endpoint without a real login?**
*Model answer:* `@WebMvcTest` + `@WithMockUser`/`@WithUserDetails`, or `MockMvc` with `SecurityMockMvcRequestPostProcessors` (`user()`, `jwt()`, `csrf()`). Assert 401/302 anonymous, 403 wrong-role/missing-CSRF, 200 right-role.
- *Follow-up: `@WithMockUser` vs `@WithUserDetails`?* Mock fabricates authorities; UserDetails uses your real service (more faithful).
- *Follow-up: Testing the JWT decoder itself?* Use `@SpringBootTest` with a mock issuer/JWKS, not the `jwt()` post-processor (which bypasses the decoder).

**Q8. (Senior signal) Your `@SpringBootTest` suite takes 25 minutes. Speed it up.**
*Model answer:* Push tests down the pyramid to slices (`@WebMvcTest`/`@DataJpaTest`); maximize context cache hits by minimizing distinct configurations (`@MockitoBean`, `@TestPropertySource`, profiles); avoid `@DirtiesContext`; reuse static Testcontainers; parallelize. Reserve full `@SpringBootTest` for critical paths.
- *Follow-up: How does context caching key work?* By the full configuration (annotations, properties, profiles, mock defs); identical keys reuse.
- *Follow-up: Cost of `@MockitoBean`?* It alters the context definition → new cache key → extra context.

**Q9. What is CORS, and why does Spring Security need to know about it?**
*Model answer:* Browser same-origin enforcement; cross-origin reads need server opt-in headers. Security must permit the preflight `OPTIONS` and emit CORS headers before authorization rejects it — hence `cors()` in the chain backed by a `CorsConfigurationSource`.
- *Follow-up: `*` with credentials?* Forbidden by spec; echo a specific origin.
- *Follow-up: Is CORS an authorization control?* No — it constrains browser JS only; it's not server-side access control.

**Q10. (Senior signal) Justify enabling vs disabling CSRF for a given app.**
*Model answer:* Enable whenever the credential is ambient/automatically attached (session cookie, Basic stored by browser). Disable only for stateless APIs where the credential is an explicit header token a cross-site page can't read. The deciding question: "Can a malicious site cause my server to act on the user's behalf using credentials the browser sends automatically?" If yes, CSRF on.
- *Follow-up: SPA with token in a cookie?* Cookie = ambient → CSRF on; use double-submit cookie (`CookieCsrfTokenRepository`).
- *Follow-up: Real failure if you disable it wrongly?* Forged state-changing requests (the transfer-form incident in §9.9).

**Q11. Explain the SecurityContext, ThreadLocal, and async propagation.**
*Model answer:* `SecurityContextHolder` keeps the context in a `ThreadLocal`; it doesn't follow new threads. Use `DelegatingSecurityContextExecutor`, `MODE_INHERITABLETHREADLOCAL`, or reactive `ReactiveSecurityContextHolder` for async/WebFlux.
- *Follow-up: Why clear the ThreadLocal at request end?* Pooled threads are reused; a stale context would leak one user's identity into another's request.
- *Follow-up: WebFlux difference?* No ThreadLocals; state lives in the Reactor `Context`.

**Q12. How does `@WithMockUser` actually inject a user?**
*Model answer:* It's `@WithSecurityContext`-meta-annotated; a `TestExecutionListener` builds and sets the context before the test and clears it after.
- *Follow-up: Multiple users in one test?* Use the per-request `user(...)` post-processor instead of the method-level annotation.
- *Follow-up: Does it hit your `UserDetailsService`?* No — that's `@WithUserDetails`.

---

## 11. Glossary

- **Anonymous authentication:** a placeholder `Authentication` (principal `anonymousUser`, `ROLE_ANONYMOUS`) set when no real auth exists, so code never sees null.
- **AOP (Aspect-Oriented Programming):** cross-cutting behavior applied via proxies/interceptors; how method security works.
- **Authentication (object):** holds principal, credentials, authorities, authenticated flag.
- **AuthenticationManager / ProviderManager:** performs authentication by delegating to providers.
- **AuthenticationProvider:** a strategy that authenticates a specific token type (e.g. `DaoAuthenticationProvider`).
- **AuthorizationManager:** SS6 functional interface returning an `AuthorizationDecision`.
- **Authority / GrantedAuthority:** a permission string; a role is one prefixed `ROLE_`.
- **bcrypt / argon2 / scrypt / PBKDF2:** password-hashing algorithms (memory/CPU-hard).
- **Bearer token:** a token presented in `Authorization: Bearer ...`; possession = authorization.
- **CGLIB / JDK dynamic proxy:** subclass-based vs interface-based proxying for AOP.
- **Chain of Responsibility:** the design pattern behind the filter chain.
- **CORS / preflight:** browser cross-origin opt-in; `OPTIONS` request that precedes some cross-origin calls.
- **CSRF / double-submit cookie:** forged-request attack and the token defense (cookie value echoed as a header).
- **DaoAuthenticationProvider:** authenticates username/password against a `UserDetailsService` + `PasswordEncoder`.
- **DelegatingFilterProxy:** servlet filter that delegates to the Spring bean `springSecurityFilterChain`.
- **DelegatingPasswordEncoder:** routes by `{id}` prefix; enables hash migration.
- **DispatcherServlet:** Spring MVC's front controller servlet.
- **ExceptionTranslationFilter:** converts security exceptions into HTTP responses (entry point / access-denied handler).
- **Filter / FilterChain:** servlet interceptors and their chain.
- **FilterChainProxy:** holds and selects `SecurityFilterChain`s.
- **Form login:** username/password POST to a login URL, session-based.
- **HTTP Basic:** `Authorization: Basic base64(user:pass)`.
- **HttpSecurity / ServerHttpSecurity:** the servlet / reactive security DSLs.
- **HttpSession / JSESSIONID:** server-side session store and its cookie.
- **JWK Set (JWKS):** issuer's public keys for verifying JWT signatures.
- **JWT:** signed `header.payload.signature` token with claims (`sub`, `exp`, `iss`, `scope`).
- **MockMvc:** in-process driver of the dispatcher servlet for tests.
- **`@MockitoBean` / `@MockBean`:** replace a context bean with a Mockito mock (former is the current name).
- **OAuth2 / OIDC:** authorization framework / authentication layer on top of it (adds ID token).
- **Opaque token / introspection:** non-self-describing token validated by an introspection endpoint.
- **PasswordEncoder:** hashes/verifies passwords.
- **Principal / credentials:** the identity and its secret.
- **ProviderManager:** see AuthenticationManager.
- **Reactor Context / ReactiveSecurityContextHolder:** WebFlux replacement for ThreadLocal security state.
- **Resource server:** validates incoming tokens (an API).
- **Role:** an authority prefixed `ROLE_`.
- **SAM interface:** single-abstract-method (lambda-able) interface.
- **Same-origin policy:** browsers restrict cross-origin reads; CORS relaxes it.
- **SecurityContext / SecurityContextHolder:** holder of the current `Authentication` (ThreadLocal by default).
- **SecurityContextHolderFilter:** SS6 filter loading/clearing the context (no auto-save).
- **SecurityFilterChain / SecurityWebFilterChain:** an ordered filter list + matcher (servlet / reactive).
- **Session fixation:** attack mitigated by changing the session id on login.
- **Servlet / servlet container:** the Jakarta HTTP standard and its runtime (Tomcat/Jetty/Undertow).
- **Slice test:** a partial-context test (`@WebMvcTest`, `@DataJpaTest`, etc.).
- **SpEL:** Spring Expression Language used in security annotations.
- **Stateful vs stateless:** session-based vs token-per-request identity.
- **TestContext caching:** reuse of `ApplicationContext`s keyed by configuration.
- **Testcontainers / `@ServiceConnection`:** real Dockerized dependencies in tests, auto-wired into Spring.
- **Test pyramid:** many unit, fewer integration, very few E2E.
- **ThreadLocal:** per-thread storage; basis of `SecurityContextHolder`.
- **UserDetails / UserDetailsService:** user record and the SAM that loads it.
- **`@WithMockUser` / `@WithUserDetails`:** inject a fabricated / real-service-loaded user into tests.

---

## 12. Cheat-sheet & self-test

### One-screen recap

- **Filter chain order (key):** ContextHolder → Cors → Csrf → Auth filters (Basic/Form/Bearer) → Anonymous → **ExceptionTranslation → Authorization** → DispatcherServlet.
- **Two questions:** AuthN (`AuthenticationManager`/`Provider`/`UserDetailsService` → `Authentication` in `SecurityContext`); AuthZ (`AuthorizationFilter`/`AuthorizationManager` vs `authorizeHttpRequests` rules).
- **Role trap:** `hasRole("ADMIN")` ⇒ authority `ROLE_ADMIN`. `hasAuthority` is literal.
- **CSRF:** ON for cookie/session apps; OFF only for stateless header-token APIs.
- **CORS:** enable `cors()` so preflight passes; explicit origins; no `*` with credentials.
- **Passwords:** `DelegatingPasswordEncoder` (`{id}` prefix); bcrypt strength 10 default (~ms), argon2id strongest. Never `NoOp`.
- **JWT:** validate `iss`/`aud`/`exp`; cache JWKS (~5 min); short lifetimes; can't revoke → refresh/opaque/deny-list.
- **SS6 changes:** lambda-only DSL; `authorizeHttpRequests`; `@EnableMethodSecurity`; `SecurityContextHolderFilter` no auto-save; `AuthorizationManager` replaces voters.
- **Method security:** `@PreAuthorize` (before, SpEL, `#args`), `@PostAuthorize` (after, `returnObject` — runs method first). AOP → self-invocation bypasses; public/non-final only.
- **Test pyramid:** unit (Mockito) → slice (`@WebMvcTest`/`@DataJpaTest`) → integration (`@SpringBootTest` + Testcontainers) → E2E. Maximize context cache hits.
- **Secured-endpoint tests:** `@WithMockUser`/`@WithUserDetails`, `MockMvc.with(csrf()|jwt()|user())`. Assert 401/302 anon, 403 wrong-role/no-CSRF, 200 right-role.
- **Async:** ThreadLocal doesn't cross threads → `DelegatingSecurityContextExecutor`; WebFlux → `ReactiveSecurityContextHolder`.
- **Default port behaviors:** session timeout 30m; SameSite `Lax`; bcrypt 2^10; JWKS cache ~5m. (Verify against your version.)

### Self-test (no answers)

1. Trace a `Bearer` JWT request through the filter chain and name the exact filter/provider/decoder that validates the signature and where authorities are derived. What gets a 401 vs a 403?
2. You add a new `@RestController` and forget to add a rule for it. With `anyRequest().authenticated()` last, what happens? With `anyRequest().permitAll()` last? Which is fail-closed and why?
3. A teammate's POST returns 403 only in the browser, never in Postman. What is the most likely cause and the correct fix (and the wrong fix)?
4. You're migrating password hashes from bcrypt to argon2id with zero downtime. Describe the exact mechanism that lets old and new hashes coexist and how you'd opportunistically upgrade.
5. Design a test that proves: (a) anonymous users are rejected, (b) a `USER` cannot hit an `ADMIN` endpoint, (c) an `ADMIN` can, and (d) state-changing calls require CSRF — using only slice tests. Which annotations/post-processors do you use, and which beans must you mock?
6. Your `@SpringBootTest` suite spawns 14 different application contexts. Explain the caching key and three concrete changes to collapse it to two contexts.
7. In a WebFlux service, `SecurityContextHolder.getContext()` returns an empty context inside a `flatMap`. Why, and what's the correct API?
