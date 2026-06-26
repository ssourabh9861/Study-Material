# Spring MVC & REST — A Definitive Engineering Handbook Chapter

> Scope note: This chapter targets **Spring Framework 6.x / Spring Boot 3.x** (Jakarta EE namespace, Java 17+) as the baseline. Where behavior differs in Spring 5 / Spring Boot 2 (the older `javax.*` world) it is flagged inline. Spring MVC is the **servlet-stack, blocking** web framework; its reactive sibling is **Spring WebFlux**, which is contrasted but not the subject here.

---

## 1. Overview & where it fits

### What it is

**Spring MVC** is the request-driven web framework that ships inside the Spring Framework (`spring-webmvc`). It implements the classic **Model–View–Controller** pattern on top of the Java **Servlet API**. Its defining characteristic is the **front controller** pattern: a single servlet — the `DispatcherServlet` — receives *every* HTTP request for the application and orchestrates a pipeline of pluggable components (handler mappings, handler adapters, argument resolvers, message converters, view resolvers, exception resolvers) to turn that request into a response.

A few foundational terms, defined immediately:

- **Servlet** — a Java object managed by a *servlet container* (Tomcat, Jetty, Undertow) that handles HTTP requests. The container parses the raw HTTP bytes, builds an `HttpServletRequest`/`HttpServletResponse` pair, and calls the servlet's `service()` method. A servlet is the lowest-level unit of "code that answers HTTP" in the Java world.
- **Servlet container** — the web server runtime that speaks HTTP/1.1 (and HTTP/2), manages a thread pool, and dispatches requests to servlets. Tomcat is the default in Spring Boot.
- **MVC (Model–View–Controller)** — a separation-of-concerns pattern. The **Model** is the data, the **View** renders it (HTML, JSON), and the **Controller** receives input and decides what to do. Spring MVC maps these to `@Controller` classes (controllers), return values + view resolvers (views), and the `Model`/`ModelAndView` objects (model).
- **Front controller** — an architectural pattern where one component is the single entry point for all requests, centralizing cross-cutting concerns (routing, security checks, logging) instead of scattering them across many servlets.

**REST** (Representational State Transfer) is an *architectural style* for networked APIs, not a Spring feature per se. Spring MVC provides first-class support for building RESTful HTTP APIs through `@RestController`, `@RequestMapping` and its shortcuts, automatic JSON (de)serialization via **HttpMessageConverters**, content negotiation, validation, and standardized error responses (`ProblemDetail`). In modern backends, "Spring MVC" overwhelmingly means "building JSON REST APIs," and that is where this chapter spends most of its energy — while still fully covering the dispatch machinery underneath.

### The problem it solves

Before Spring MVC, building a Java web app meant either writing raw servlets (one per endpoint, manual request parsing, manual response writing, manual content-type handling) or wrestling with heavyweight frameworks (Struts, JSF) that imposed rigid lifecycles and verbose XML. The pain points:

1. **Boilerplate per endpoint** — parsing query params, headers, request bodies, and writing the response by hand.
2. **No clean mapping** from URL + HTTP method to a method that handles it.
3. **No uniform serialization** — turning a Java object into JSON and back was manual.
4. **Scattered cross-cutting concerns** — auth, logging, error handling repeated everywhere.

Spring MVC collapses all of this into a **declarative, annotation-driven model**: you write a plain method, annotate it with `@GetMapping("/orders/{id}")`, declare the inputs you want as typed method parameters (`@PathVariable Long id`, `@RequestBody OrderRequest body`), return a POJO, and the framework handles routing, binding, validation, serialization, content negotiation, and error mapping.

### When you reach for it

- You are building a **synchronous, blocking** HTTP API or server-rendered web app on the JVM.
- You want the mature, battle-tested servlet stack with the enormous Spring ecosystem (Security, Data, Actuator) integrated out of the box.
- Your workload is **thread-per-request** friendly: moderate concurrency, blocking I/O (JDBC, blocking HTTP clients), and you are *not* trying to handle tens of thousands of concurrent long-lived connections on a tiny thread pool.

You reach for **WebFlux** instead when you need non-blocking, reactive, backpressure-aware handling of very high concurrency with non-blocking I/O all the way down. (With **virtual threads** in Java 21+, plain Spring MVC regains a lot of the scalability story for blocking code — covered in §7.)

### One-paragraph mental model

> A single servlet, the `DispatcherServlet`, is the front door. Every request enters it. It asks a **HandlerMapping**, "which handler (controller method) owns this URL+method?" It then asks a **HandlerAdapter** to *invoke* that handler — and the adapter's job is to resolve each method argument (from path, query, headers, body) using **argument resolvers** and **HttpMessageConverters**, call your method, then take the return value and turn it into a response (again via converters for `@ResponseBody`, or via a **ViewResolver** for view names). If anything throws, a chain of **HandlerExceptionResolvers** turns the exception into a clean HTTP response. Filters wrap the whole servlet; interceptors wrap the handler invocation; AOP wraps the method call itself. That's the entire framework in one breath.

---

## 2. Foundations from first principles

We build up from the raw HTTP request to a fully wired Spring MVC application, defining each term as it appears.

### 2.1 The HTTP request, concretely

An HTTP request is text (HTTP/1.1) or binary frames (HTTP/2) on a TCP socket. A typical request:

```
POST /api/orders HTTP/1.1
Host: shop.example.com
Content-Type: application/json
Accept: application/json
Authorization: Bearer eyJhbGc...
Content-Length: 54

{"sku":"ABC-123","quantity":2,"currency":"USD"}
```

Components:
- **Request line**: method (`POST`), request target / path (`/api/orders`), protocol version.
- **Headers**: key-value metadata. `Content-Type` describes the body's format; `Accept` says what formats the client will accept back; `Authorization` carries credentials.
- **Body**: the payload (here JSON). May be absent (GET).

The response mirrors this: a **status line** (`HTTP/1.1 201 Created`), headers, and an optional body.

- **HTTP method (verb)** — `GET` (read, safe, idempotent), `POST` (create / non-idempotent action), `PUT` (full replace, idempotent), `PATCH` (partial update), `DELETE` (remove, idempotent), `HEAD` (GET without body), `OPTIONS` (capabilities, used by CORS preflight).
  - **Safe** = does not change server state. **Idempotent** = doing it N times has the same effect as doing it once.
- **Status code** — 3-digit result. `2xx` success (`200 OK`, `201 Created`, `204 No Content`), `3xx` redirection (`301`, `304 Not Modified`), `4xx` client error (`400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `409 Conflict`, `422 Unprocessable Entity`, `429 Too Many Requests`), `5xx` server error (`500 Internal Server Error`, `502 Bad Gateway`, `503 Service Unavailable`).

### 2.2 The servlet contract

A servlet container hands your application requests via the `jakarta.servlet.Servlet` interface (Spring 6 / Boot 3) — in Spring 5 / Boot 2 it was `javax.servlet.Servlet`. The Jakarta rename (the "big bang" namespace migration) is the single most disruptive change between the two generations and is why you cannot mix Spring 5 and 6 dependencies.

The contract: the container calls `service(HttpServletRequest, HttpServletResponse)`. `HttpServletServlet`'s default `service()` dispatches to `doGet`, `doPost`, etc. Spring's `DispatcherServlet` extends `FrameworkServlet` extends `HttpServletBean` extends `HttpServlet`, overriding `doService()` to run the dispatch pipeline.

- **`HttpServletRequest`** — accessor for everything about the inbound request: `getParameter`, `getHeader`, `getInputStream`, `getMethod`, `getRequestURI`, attributes (request-scoped key/value bag).
- **`HttpServletResponse`** — sink for the outbound response: `setStatus`, `setHeader`, `getOutputStream`/`getWriter`.
- **Filter (`jakarta.servlet.Filter`)** — a container-level interceptor that wraps `service()`. Filters form a **chain**; each can inspect/modify request and response and decide whether to pass control onward via `chain.doFilter(...)`. Filters run *before* the request even reaches the `DispatcherServlet` and *after* it returns. This is where Spring Security, CORS, request logging, and compression typically live.

### 2.3 The Spring application context

- **IoC (Inversion of Control) container** — Spring's core. Instead of your code constructing its dependencies, the container constructs and wires them (**Dependency Injection / DI**). The container is represented by an `ApplicationContext`.
- **Bean** — any object managed by the Spring container. You declare beans with `@Component`, `@Service`, `@Repository`, `@Controller`, `@RestController`, `@Configuration` + `@Bean`, etc.
- **Component scanning** — Spring scans packages for these stereotype annotations and registers the classes as beans automatically.

Spring MVC lives inside a `WebApplicationContext` (a web-aware `ApplicationContext`). Classically there were *two* contexts: a **root** context (services, repositories, shared beans) and a **child** servlet/web context (controllers, view resolvers, the MVC infrastructure), where the child can see root beans but not vice versa. In Spring Boot, this distinction is usually collapsed into a single context for simplicity, though the parent/child capability remains.

### 2.4 The stereotypes that make a controller

- **`@Controller`** — marks a class as a web controller (a bean). Its methods return view names by default (server-side rendering) or `@ResponseBody` per method for raw bodies.
- **`@ResponseBody`** — on a method (or class), tells Spring "do not resolve a view; instead serialize the return value directly into the response body" using an `HttpMessageConverter`.
- **`@RestController`** — a meta-annotation = `@Controller` + `@ResponseBody`. Every method's return value is serialized to the body. This is what you use for JSON REST APIs.
- **`@RequestMapping`** — maps requests to handler methods/classes by path, method, headers, params, content type, and accept type. Shortcuts: `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping`.

### 2.5 A minimal end-to-end example

```java
// build.gradle: implementation 'org.springframework.boot:spring-boot-starter-web'

@SpringBootApplication                 // enables auto-config + component scan
public class ShopApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}

@RestController                          // = @Controller + @ResponseBody
@RequestMapping("/api/orders")           // class-level base path
class OrderController {

    @GetMapping("/{id}")                 // GET /api/orders/42
    OrderResponse getOrder(@PathVariable Long id) {
        return new OrderResponse(id, "ABC-123", 2);   // serialized to JSON automatically
    }

    @PostMapping                         // POST /api/orders
    @ResponseStatus(HttpStatus.CREATED)  // return 201 instead of default 200
    OrderResponse create(@RequestBody @Valid OrderRequest req) {
        return new OrderResponse(99L, req.sku(), req.quantity());
    }
}

record OrderRequest(@NotBlank String sku, @Min(1) int quantity) {}
record OrderResponse(Long id, String sku, int quantity) {}
```

With `spring-boot-starter-web` on the classpath, Spring Boot auto-configures an embedded Tomcat, registers the `DispatcherServlet` at `/`, registers Jackson for JSON, registers the validation infrastructure, and scans for `@RestController` beans. There is no `web.xml`, no XML config, no explicit servlet registration. That entire stack is the subject of §3.

---

## 3. How it works internally — the DispatcherServlet request lifecycle

This is the heart of the chapter. We trace a single request from the socket to the response, naming every collaborator and explaining what it does and why.

### 3.1 Bootstrap: how the DispatcherServlet comes to exist

In Spring Boot:
1. `SpringApplication.run` builds the `ApplicationContext` and, because `spring-boot-starter-web` is present, selects a `ServletWebServerApplicationContext`.
2. Auto-configuration (`DispatcherServletAutoConfiguration`, `WebMvcAutoConfiguration`) registers a `DispatcherServlet` bean and a `DispatcherServletRegistrationBean` mapping it to path `/` (configurable via `spring.mvc.servlet.path`).
3. An embedded `ServletWebServerFactory` (Tomcat by default) starts the server and registers the servlet and any filters.
4. On first use (or eagerly), the `DispatcherServlet.onRefresh()` runs `initStrategies()`, which initializes its pluggable components by looking them up as beans (or falling back to defaults in `DispatcherServlet.properties`).

The strategies initialized (each is a list of beans, ordered):

| Strategy field | Type | Role |
|---|---|---|
| `handlerMappings` | `List<HandlerMapping>` | URL+method → handler |
| `handlerAdapters` | `List<HandlerAdapter>` | knows how to *invoke* a given handler type |
| `handlerExceptionResolvers` | `List<HandlerExceptionResolver>` | exception → response |
| `viewResolvers` | `List<ViewResolver>` | view name → `View` |
| `localeResolver` | `LocaleResolver` | determine request locale |
| `themeResolver` | `ThemeResolver` | (legacy) theming |
| `multipartResolver` | `MultipartResolver` | parse `multipart/form-data` uploads |
| `flashMapManager` | `FlashMapManager` | flash attributes across redirects |
| `requestToViewNameTranslator` | — | default view name from URL |

(In Spring 6.1 `ThemeResolver`/theme support is deprecated.)

### 3.2 The dispatch pipeline, step by step

When a request arrives, the container calls `service()` → `FrameworkServlet.processRequest()` → `DispatcherServlet.doService()` → **`doDispatch(request, response)`**. Here is the full ordered flow:

**Step 0 — Pre-dispatch setup (`doService`)**
- Binds request-scoped context: stores the `WebApplicationContext`, `LocaleResolver`, `ThemeResolver`, and a fresh/inherited `FlashMap` as request attributes so downstream code can find them.
- Snapshots request attributes for `include` requests (so they can be restored).

**Step 1 — Multipart check**
- `checkMultipart()`: if `Content-Type` is `multipart/...` and a `MultipartResolver` is present, the request is wrapped in a `MultipartHttpServletRequest`, parsing file parts. (Spring Boot uses `StandardServletMultipartResolver` backed by the Servlet 3.0+ container.)

**Step 2 — Handler resolution (`getHandler`)**
- Iterates `handlerMappings` in order, calling `getHandler(request)` on each. The first non-null result wins.
- The result is a **`HandlerExecutionChain`**: the handler object *plus* the ordered list of **`HandlerInterceptor`s** that apply to it.
- For annotated controllers, the mapping is `RequestMappingHandlerMapping`, which at startup built a `Map<RequestMappingInfo, HandlerMethod>` by scanning all `@RequestMapping` methods. `RequestMappingInfo` encapsulates the path pattern, methods, params, headers, consumes, and produces conditions. Matching considers all of these; ambiguous matches throw, best-match wins by specificity (e.g., exact path > pattern, more-specific media type wins).
- **`HandlerMethod`** — a wrapper around (bean, `java.lang.reflect.Method`) plus precomputed metadata about the parameters and return type.
- If no handler matches → `noHandlerFound()` → typically a `NoHandlerFoundException` or a 404 (Boot's behavior depends on `spring.mvc.throw-exception-if-no-handler-found`, which defaults to `true` in Boot 3 with `ErrorMvcAutoConfiguration` handling the 404 page).

**Step 3 — Adapter selection (`getHandlerAdapter`)**
- Iterates `handlerAdapters`, calling `supports(handler)`. The first that supports the handler type is used.
- For `HandlerMethod`s, this is **`RequestMappingHandlerAdapter`** — the workhorse that does argument resolution, invocation, and return-value handling.
- Other adapters exist for older handler types (`HttpRequestHandlerAdapter`, `SimpleControllerHandlerAdapter`, and `HandlerFunctionAdapter` for functional routing).

**Step 4 — `preHandle` interceptors**
- `mappedHandler.applyPreHandle(...)` calls each interceptor's `preHandle()` in order. If any returns `false`, the chain short-circuits: the already-invoked interceptors' `afterCompletion` is called and dispatch ends (no controller runs). This is how an auth interceptor can reject a request before the controller.

**Step 5 — Handler invocation (`ha.handle(...)`)** — the deep part, expanded in §3.3.

**Step 6 — Default view name**
- If the handler returned a `ModelAndView` without a view name, `applyDefaultViewName` derives one from the URL via `RequestToViewNameTranslator`.

**Step 7 — `postHandle` interceptors**
- After the handler returns (but before rendering), each interceptor's `postHandle()` runs in *reverse* order, getting access to the `ModelAndView` to tweak the model/view.

**Step 8 — `processDispatchResult`**
- If an exception was thrown anywhere in steps 5–7, it is captured and routed through `processHandlerException` (→ §3.5). Otherwise:
- If there is a `ModelAndView` with a view, `render()` runs: resolve the view name via `viewResolvers` into a `View`, then `view.render(model, request, response)` writes output (JSP, Thymeleaf, etc.).
- For `@ResponseBody`/`@RestController`, there is **no** `ModelAndView` to render — the response was already written during return-value handling in step 5 (the `RequestResponseBodyMethodProcessor` wrote the body via a message converter, and `mavContainer.setRequestHandled(true)` was set so `doDispatch` skips rendering).

**Step 9 — `afterCompletion` interceptors**
- `triggerAfterCompletion` calls each interceptor's `afterCompletion()` in reverse order, *always* (even on exception). This is the "finally" hook — cleanup, timing, MDC teardown.

**Step 10 — Cleanup**
- `finally`: restore snapshotted attributes (for includes), clean up multipart resources, publish a `ServletRequestHandledEvent`.

### 3.3 Inside `RequestMappingHandlerAdapter` — invoking the controller method

This is where the "magic" of declarative parameters lives. `handle()` delegates to `invokeHandlerMethod()`, which:

1. Builds a **`ServletWebRequest`** and a **`ServletInvocableHandlerMethod`** (the `HandlerMethod` + the strategies to resolve args and handle the return value).
2. Sets up the **`ModelAndViewContainer`** (`mavContainer`) — the accumulator for the model and view/response state.
3. Applies `@InitBinder` methods (to register custom `PropertyEditor`s / `Formatter`s / `Validator`s on the `WebDataBinder`) and `@ModelAttribute` methods (to pre-populate the model).
4. **Resolves arguments** — for each method parameter, it walks the ordered list of **`HandlerMethodArgumentResolver`s**; the first whose `supportsParameter()` returns true does `resolveArgument()`. (Catalog in §4.)
5. **Invokes** the method via reflection with the resolved args.
6. **Handles the return value** — walks the ordered list of **`HandlerMethodReturnValueHandler`s**; the first that `supportsReturnType()` does `handleReturnValue()`. For a `@ResponseBody`/`@RestController` return, this is `RequestResponseBodyMethodProcessor`, which picks an `HttpMessageConverter` (content negotiation) and **writes the body directly to the response**, then marks the request as handled.

#### Argument resolution in detail (the data flow)

Given `create(@RequestBody @Valid OrderRequest req)`:
- The resolver `RequestResponseBodyMethodProcessor.supportsParameter` sees `@RequestBody` → it reads the request `InputStream`, inspects `Content-Type`, finds a compatible `HttpMessageConverter` (Jackson for `application/json`), and **deserializes** the body into an `OrderRequest`.
- Because `@Valid` is present, it then runs Bean Validation; violations become a `MethodArgumentNotValidException`.

Given `getOrder(@PathVariable Long id)`:
- `PathVariableMethodArgumentResolver` extracts the URI template variable `id` (captured during handler mapping), then converts the `String "42"` to `Long` via the `ConversionService`.

#### Return-value handling in detail

For `OrderResponse getOrder(...)` in a `@RestController`:
- `RequestResponseBodyMethodProcessor.handleReturnValue`:
  1. Performs **content negotiation**: computes the list of acceptable media types from the `Accept` header (and config), intersects with the types the converters/`@RequestMapping(produces=...)` can produce.
  2. Selects the best `HttpMessageConverter` that `canWrite(OrderResponse.class, mediaType)`.
  3. Calls `converter.write(value, mediaType, outputMessage)` — Jackson serializes the POJO to JSON onto the response output stream.
  4. Sets `mavContainer.setRequestHandled(true)` so the dispatcher does not attempt view rendering.

### 3.4 The component-wiring sequence (control flow summary)

```
Socket → Tomcat thread → Filter chain (Security, CORS, logging…) 
  → DispatcherServlet.service → doDispatch
     → MultipartResolver?
     → HandlerMapping.getHandler → HandlerExecutionChain (handler + interceptors)
     → HandlerAdapter.supports → RequestMappingHandlerAdapter
     → interceptor.preHandle (in order)        [may short-circuit]
     → RequestMappingHandlerAdapter.handle
          → @InitBinder / @ModelAttribute setup
          → ArgumentResolvers resolve each param (body via HttpMessageConverter, @Valid)
          → reflective method.invoke(controller, args)
          → ReturnValueHandler (writes body via HttpMessageConverter, content negotiation)
     → interceptor.postHandle (reverse order)
     → render view (only if ModelAndView with a view; skipped for @ResponseBody)
     → [on any exception] HandlerExceptionResolvers
     → interceptor.afterCompletion (reverse order, always)
  → Filter chain unwinds → response flushed to socket
```

### 3.5 Exception handling internals

If steps 5–8 throw, `processHandlerException` iterates `handlerExceptionResolvers` in order. The default chain (registered by `WebMvcConfigurationSupport`):

1. **`ExceptionHandlerExceptionResolver`** — finds an `@ExceptionHandler` method (on the controller itself, then on `@ControllerAdvice` beans) whose declared exception type matches (most specific wins). It invokes that method *exactly like a controller method* (argument resolvers, return-value handlers — so an `@ExceptionHandler` can `@ResponseBody` a `ProblemDetail`).
2. **`ResponseStatusExceptionResolver`** — handles `ResponseStatusException` and exceptions annotated `@ResponseStatus`, mapping them to a status code.
3. **`DefaultHandlerExceptionResolver`** — translates standard Spring MVC exceptions (e.g., `HttpRequestMethodNotSupportedException` → 405, `HttpMessageNotReadableException` → 400, `MethodArgumentNotValidException` → 400, `NoHandlerFoundException` → 404) into status codes.

If no resolver handles it, the exception propagates to the container, which forwards to the error dispatch (`/error`). In Spring Boot, `BasicErrorController` + `ErrorMvcAutoConfiguration` produce the default JSON/HTML error response (and, since Boot 3, optionally a `ProblemDetail`-shaped body). Spring 6 added native `ProblemDetail` (RFC 9457, the obsoleter of RFC 7807) support and `ResponseEntityExceptionHandler` now returns `ProblemDetail` bodies.

### 3.6 Async, streaming, and the lifecycle variations

Spring MVC supports asynchronous request processing without blocking the container thread for the whole duration:
- Returning a **`Callable<T>`** → Spring runs it on a `TaskExecutor`, releasing the Tomcat thread; on completion it re-dispatches to finish the response.
- Returning a **`DeferredResult<T>`** → you complete it from any thread later (e.g., when a message arrives). Tomcat thread is freed immediately.
- Returning **`ResponseBodyEmitter` / `SseEmitter` / `StreamingResponseBody`** → stream chunks/Server-Sent-Events progressively.
- The lifecycle here uses **Servlet 3.0 async** (`request.startAsync()`); the dispatch runs twice (the initial dispatch starts async, a later `ASYNC` dispatch resumes). Interceptors that implement `AsyncHandlerInterceptor` get `afterConcurrentHandlingStarted` instead of `postHandle` on the first pass.

---

## 4. The complete toolkit

### 4.1 Controller & mapping annotations

| Annotation | Applies to | Purpose | Key attributes / notes |
|---|---|---|---|
| `@Controller` | class | Web controller bean | view-returning by default |
| `@RestController` | class | `@Controller` + `@ResponseBody` | every method body serialized |
| `@RequestMapping` | class/method | Map requests | `path`/`value`, `method`, `params`, `headers`, `consumes`, `produces` |
| `@GetMapping` etc. | method | Shortcut for verb | `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping` |
| `@ResponseBody` | class/method | Serialize return to body | implied by `@RestController` |
| `@ResponseStatus` | method/exception | Set status code | e.g. `HttpStatus.CREATED` |
| `@ExceptionHandler` | method | Handle exceptions locally/globally | declares exception type(s) |
| `@ControllerAdvice` / `@RestControllerAdvice` | class | Global advice (exception/binder/model) | `basePackages`, `assignableTypes`, `annotations` to scope |
| `@CrossOrigin` | class/method | Per-handler CORS | `origins`, `methods`, `allowedHeaders`, `maxAge` |
| `@InitBinder` | method | Customize data binding | register `Formatter`/`Validator`/allowed fields |
| `@ModelAttribute` | method/param | Populate/bind model object | binds form/query params to an object |

### 4.2 Method-parameter annotations (inputs)

| Annotation | Source | Notes / defaults |
|---|---|---|
| `@PathVariable` | URI template var | `@GetMapping("/{id}")` → `@PathVariable Long id`; `required=true` |
| `@RequestParam` | query/form param | `defaultValue`, `required` (default true); `Map<String,String>` grabs all |
| `@RequestHeader` | header | single header or `Map`/`HttpHeaders` for all |
| `@RequestBody` | request body | deserialized via `HttpMessageConverter`; pair with `@Valid` |
| `@RequestPart` | multipart part | for `multipart/form-data` (file + JSON parts) |
| `@CookieValue` | cookie | |
| `@MatrixVariable` | matrix URI params | off by default; enable via config |
| `@ModelAttribute` | aggregate binding | binds many params into one object |
| `@SessionAttribute` / `@RequestAttribute` | session/request attrs | |
| `@Valid` / `@Validated` | (JSR/Spring) triggers validation | `@Validated` supports validation groups |

Resolvable parameter *types* (no annotation needed): `HttpServletRequest`/`Response`, `HttpEntity<T>`, `Model`/`ModelMap`, `Errors`/`BindingResult` (must immediately follow the validated object), `Locale`, `TimeZone`, `Principal`, `UriComponentsBuilder`, `Pageable` (with Spring Data), etc.

### 4.3 Return types (outputs)

| Return type | Behavior |
|---|---|
| POJO / `record` / collection | serialized to body (in `@RestController`) |
| `ResponseEntity<T>` | full control over status, headers, body |
| `ResponseEntity<Void>` | status + headers, no body (e.g., `201` + `Location`) |
| `String` (in `@Controller`) | view name |
| `ModelAndView` | model + view |
| `void` | response written directly, or view name from URL |
| `Callable<T>`, `DeferredResult<T>`, `CompletableFuture<T>` | async |
| `ResponseBodyEmitter`, `SseEmitter`, `StreamingResponseBody` | streaming |
| `ProblemDetail` | RFC 9457 error body |
| `HttpHeaders` | headers-only response |

### 4.4 HttpMessageConverters (the (de)serialization layer)

An **`HttpMessageConverter<T>`** converts between Java objects and HTTP request/response bodies. It has `canRead(type, mediaType)`, `read(...)`, `canWrite(type, mediaType)`, `write(...)`, and `getSupportedMediaTypes()`. Spring Boot auto-registers a default ordered list:

| Converter | Media types | Notes |
|---|---|---|
| `MappingJackson2HttpMessageConverter` | `application/json`, `application/*+json` | default JSON; needs Jackson on classpath |
| `ByteArrayHttpMessageConverter` | `*/*` | raw bytes |
| `StringHttpMessageConverter` | `text/plain` | charset-aware (default UTF-8 in Boot) |
| `ResourceHttpMessageConverter` | `*/*` | `org.springframework.core.io.Resource` (file downloads) |
| `FormHttpMessageConverter` | `application/x-www-form-urlencoded`, `multipart/form-data` | form data |
| `MappingJackson2XmlHttpMessageConverter` | `application/xml` | if `jackson-dataformat-xml` present |
| `Jaxb2RootElementHttpMessageConverter` | XML | if JAXB present and Jackson XML absent |
| `MappingJackson2CborHttpMessageConverter` | `application/cbor` | if jackson-dataformat-cbor present |
| (Spring 6.2+) `KotlinSerializationJsonHttpMessageConverter`, `JsonbHttpMessageConverter`, `GsonHttpMessageConverter` | json | alternatives if their libs present |

To customize, implement `WebMvcConfigurer.configureMessageConverters` (replace) or `extendMessageConverters` (tweak), or expose a `Jackson2ObjectMapperBuilderCustomizer` bean (Boot) to configure Jackson.

### 4.5 Content negotiation

**Content negotiation** is deciding the response media type. The `ContentNegotiationManager` uses strategies in order:
1. **Header strategy** (default, on) — the `Accept` header.
2. **Parameter strategy** (off by default) — e.g., `?format=json`. Enable with `spring.mvc.contentnegotiation.favor-parameter=true` (param name `format` by default).
3. **Path-extension strategy** — *removed/disabled by default in Spring 5.3+/6* for security reasons (RFD attacks); legacy.

Relevant Boot properties:
- `spring.mvc.contentnegotiation.favor-parameter` (default `false`)
- `spring.mvc.contentnegotiation.parameter-name` (default `format`)
- `spring.mvc.contentnegotiation.media-types.*` (map extensions to media types)

### 4.6 Validation

- **Bean Validation (Jakarta Validation, JSR 380 / Jakarta Validation 3.0)** — declarative constraints via annotations: `@NotNull`, `@NotBlank`, `@NotEmpty`, `@Size`, `@Min`/`@Max`, `@Positive`, `@Email`, `@Pattern`, `@Past`/`@Future`, `@Valid` (cascade). Implemented by **Hibernate Validator** (the reference implementation; unrelated to Hibernate ORM despite the name). Add `spring-boot-starter-validation` to get it.
- **`@Valid`** triggers validation on a `@RequestBody`/`@ModelAttribute`/`@RequestPart` argument.
- **`@Validated`** (Spring's) — class-level enables method-level validation of `@RequestParam`/`@PathVariable`; also supports **validation groups** (validate different constraint subsets for create vs update).
- Failures: `@RequestBody` → `MethodArgumentNotValidException`; `@ModelAttribute` → `BindException`; method params → `HandlerMethodValidationException` (Spring 6.1+) / `ConstraintViolationException` (older). All map to **400** by default.

### 4.7 Error/problem types

| Type | Purpose |
|---|---|
| `ResponseStatusException` | throw with a status + reason without a custom exception class |
| `@ResponseStatus` on exception | map a custom exception to a status |
| `ProblemDetail` | RFC 9457 standardized error body (`type`, `title`, `status`, `detail`, `instance`, extensions) |
| `ErrorResponse` / `ErrorResponseException` | interface/exception carrying a `ProblemDetail` (Spring 6) |
| `ResponseEntityExceptionHandler` | base class for `@ControllerAdvice` that maps standard exceptions to `ProblemDetail` |

### 4.8 Cross-cutting components

| Mechanism | Granularity | Sees | Use for |
|---|---|---|---|
| **Filter** | before/after DispatcherServlet | raw request/response | security, CORS, compression, request logging, MDC, rate limiting |
| **HandlerInterceptor** | around handler invocation | handler, ModelAndView | auth checks tied to handlers, timing, locale, adding model attrs |
| **AOP advice** | around any bean method | method args/return | service-layer concerns, transactions, retries |
| **`@ControllerAdvice`** | across controllers | exceptions, binders, model | global error handling, global binding rules |

### 4.9 Configuration surface (Spring Boot `application.properties`)

| Property | Default | Meaning |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `spring.mvc.servlet.path` | `/` | DispatcherServlet base path |
| `server.servlet.context-path` | `/` | app context path |
| `spring.mvc.throw-exception-if-no-handler-found` | `true` (Boot 3) | 404 → exception |
| `spring.web.resources.add-mappings` | `true` | serve static resources |
| `spring.jackson.*` | — | Jackson config (date format, inclusion, naming) |
| `spring.mvc.format.date-time` | — | default datetime parse/format pattern |
| `server.tomcat.threads.max` | `200` | Tomcat worker threads |
| `server.tomcat.threads.min-spare` | `10` | min spare threads |
| `server.tomcat.max-connections` | `8192` | max simultaneous connections |
| `server.tomcat.accept-count` | `100` | accept queue length |
| `spring.servlet.multipart.max-file-size` | `1MB` | per-file upload cap |
| `spring.servlet.multipart.max-request-size` | `10MB` | total request cap |
| `spring.threads.virtual.enabled` | `false` | use virtual threads (Java 21+) |

### 4.10 Programmatic configuration: `WebMvcConfigurer`

Implement `WebMvcConfigurer` (a bean) to customize without losing Boot auto-config (do **not** add `@EnableWebMvc`, which disables it). Key callbacks:

```java
@Configuration
class WebConfig implements WebMvcConfigurer {
    public void addInterceptors(InterceptorRegistry r) { /* register HandlerInterceptors */ }
    public void addCorsMappings(CorsRegistry r) { /* global CORS */ }
    public void addFormatters(FormatterRegistry r) { /* custom Formatter/Converter */ }
    public void configureContentNegotiation(ContentNegotiationConfigurer c) {}
    public void extendMessageConverters(List<HttpMessageConverter<?>> c) {}
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> r) {}
    public void configurePathMatch(PathMatchConfigurer c) {}
    public void addReturnValueHandlers(List<HandlerMethodReturnValueHandler> h) {}
}
```

---

## 5. Code examples by use case

### 5.1 A clean CRUD REST resource with `ResponseEntity`, validation, and 201/Location

```java
@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    private final OrderService service;
    OrderController(OrderService service) { this.service = service; }   // constructor injection (preferred)

    // GET collection with paging + filtering
    @GetMapping
    Page<OrderDto> list(@RequestParam(required = false) String status,
                        @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return service.search(status, pageable).map(OrderDto::from);
    }

    // GET one — 404 if missing (handled globally, see 5.4)
    @GetMapping("/{id}")
    OrderDto get(@PathVariable Long id) {
        return OrderDto.from(service.getOrThrow(id));
    }

    // CREATE — returns 201 + Location header, the idiomatic REST way
    @PostMapping
    ResponseEntity<OrderDto> create(@RequestBody @Valid CreateOrderRequest req,
                                    UriComponentsBuilder uri) {
        Order created = service.create(req);
        URI location = uri.path("/api/v1/orders/{id}")
                          .buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(OrderDto.from(created));
    }

    // Full replace — idempotent PUT
    @PutMapping("/{id}")
    OrderDto replace(@PathVariable Long id, @RequestBody @Valid ReplaceOrderRequest req) {
        return OrderDto.from(service.replace(id, req));
    }

    // Partial update — PATCH
    @PatchMapping("/{id}")
    OrderDto patch(@PathVariable Long id, @RequestBody JsonNode patch) {
        return OrderDto.from(service.applyPatch(id, patch));
    }

    // DELETE — 204 No Content
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long id) {
        service.delete(id);
    }
}

record CreateOrderRequest(
    @NotBlank String sku,
    @Min(1) @Max(1000) int quantity,
    @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {}
```

Why these choices: constructor injection makes dependencies explicit and the bean immutable/testable; `ResponseEntity.created(location)` produces `201` with the `Location` header (REST convention for "where the new resource lives"); `204` for delete signals success with no body; `Page` integrates with Spring Data paging.

### 5.2 Global exception handling with `@RestControllerAdvice` and `ProblemDetail`

```java
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // Domain "not found" → 404 ProblemDetail
    @ExceptionHandler(EntityNotFoundException.class)
    ProblemDetail handleNotFound(EntityNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://errors.example.com/not-found"));
        pd.setTitle("Resource not found");
        pd.setProperty("timestamp", Instant.now());     // RFC 9457 extension member
        return pd;
    }

    // Domain conflict → 409
    @ExceptionHandler(OptimisticLockException.class)
    ProblemDetail handleConflict(OptimisticLockException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
            "The resource was modified concurrently; retry with the latest version.");
    }

    // Override the framework's bean-validation handler to shape field errors
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation failed");
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
          .forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));
        pd.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(pd);
    }
}
```

`ResponseEntityExceptionHandler` already maps standard Spring MVC exceptions (415, 405, 400, 404, etc.) to `ProblemDetail` in Spring 6; you override only the ones you want to customize and add your domain exceptions. A sample 404 body:

```json
{
  "type": "https://errors.example.com/not-found",
  "title": "Resource not found",
  "status": 404,
  "detail": "Order 42 not found",
  "instance": "/api/v1/orders/42",
  "timestamp": "2026-06-24T10:15:30Z"
}
```

### 5.3 Content negotiation: same controller serving JSON and XML

```java
// build: add com.fasterxml.jackson.dataformat:jackson-dataformat-xml
@GetMapping(value = "/{id}", produces = { MediaType.APPLICATION_JSON_VALUE,
                                          MediaType.APPLICATION_XML_VALUE })
OrderDto get(@PathVariable Long id) { return OrderDto.from(service.getOrThrow(id)); }
```

- `Accept: application/json` → Jackson JSON converter chosen.
- `Accept: application/xml` → Jackson XML converter chosen.
- To enable `?format=xml`-style negotiation: `spring.mvc.contentnegotiation.favor-parameter=true`.

### 5.4 File upload (multipart) and download (streaming)

```java
@PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
ResponseEntity<ImportSummary> importFile(
        @RequestPart("file") MultipartFile file,
        @RequestPart("meta") @Valid ImportMeta meta) throws IOException {
    try (InputStream in = file.getInputStream()) {                 // stream; do not load whole file
        return ResponseEntity.ok(service.ingest(in, file.getOriginalFilename(), meta));
    }
}

@GetMapping("/{id}/invoice")
ResponseEntity<StreamingResponseBody> download(@PathVariable Long id) {
    StreamingResponseBody body = out -> service.streamInvoicePdf(id, out);   // chunked, low memory
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoice-" + id + ".pdf\"")
        .body(body);
}
```

### 5.5 A `HandlerInterceptor` for request timing + correlation ID

```java
@Component
class TimingInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(TimingInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        req.setAttribute("startNanos", System.nanoTime());
        return true;                                  // false would abort dispatch
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res,
                                Object handler, Exception ex) {
        long start = (long) req.getAttribute("startNanos");
        long ms = (System.nanoTime() - start) / 1_000_000;
        log.info("{} {} -> {} in {}ms", req.getMethod(), req.getRequestURI(), res.getStatus(), ms);
    }
}

@Configuration
class WebConfig implements WebMvcConfigurer {
    private final TimingInterceptor timing;
    WebConfig(TimingInterceptor timing) { this.timing = timing; }
    @Override public void addInterceptors(InterceptorRegistry r) {
        r.addInterceptor(timing).addPathPatterns("/api/**");
    }
}
```

### 5.6 A servlet `Filter` for a correlation/trace ID into the logging MDC

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)               // run early in the filter chain
class CorrelationIdFilter extends OncePerRequestFilter {  // guarantees single execution per dispatch
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String id = Optional.ofNullable(req.getHeader("X-Correlation-Id"))
                            .orElse(UUID.randomUUID().toString());
        MDC.put("correlationId", id);             // appears in every log line via the layout pattern
        res.setHeader("X-Correlation-Id", id);
        try { chain.doFilter(req, res); }
        finally { MDC.clear(); }                  // critical: clear on thread-pooled threads
    }
}
```

`OncePerRequestFilter` is the base class you almost always want: it stores a request attribute so the filter does not re-run on async/forward dispatches.

### 5.7 Custom argument resolver (inject the current authenticated user)

```java
@Target(ElementType.PARAMETER) @Retention(RetentionPolicy.RUNTIME)
@interface CurrentUser {}

@Component
class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {
    @Override public boolean supportsParameter(MethodParameter p) {
        return p.hasParameterAnnotation(CurrentUser.class) && p.getParameterType().equals(AppUser.class);
    }
    @Override public Object resolveArgument(MethodParameter p, ModelAndViewContainer mav,
            NativeWebRequest req, WebDataBinderFactory bf) {
        Principal principal = req.getNativeRequest(HttpServletRequest.class).getUserPrincipal();
        return userService.load(principal.getName());
    }
}
// register via WebMvcConfigurer.addArgumentResolvers(...)
// usage: void getProfile(@CurrentUser AppUser user) { ... }
```

### 5.8 Async non-blocking offload with `CompletableFuture`

```java
@GetMapping("/{id}/report")
CompletableFuture<ReportDto> report(@PathVariable Long id) {
    // Tomcat thread is released; completes on the executor thread, then re-dispatches.
    return CompletableFuture.supplyAsync(() -> service.buildReport(id), reportExecutor);
}
```

### 5.9 Server-Sent Events stream

```java
@GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
SseEmitter events(@PathVariable Long id) {
    SseEmitter emitter = new SseEmitter(Duration.ofMinutes(10).toMillis());
    eventBus.subscribe(id, evt -> {
        try { emitter.send(SseEmitter.event().name(evt.type()).data(evt)); }
        catch (IOException e) { emitter.completeWithError(e); }
    });
    emitter.onCompletion(() -> eventBus.unsubscribe(id));
    return emitter;
}
```

---

## 6. Implementation concerns & best practices

### 6.1 Performance

- **Thread-per-request model**: each request occupies a Tomcat worker thread for its full duration. Blocking I/O (slow DB, slow downstream HTTP) ties up threads. With the default `server.tomcat.threads.max=200`, you can serve ~200 concurrent in-flight requests; beyond that they queue (`accept-count`) or get refused. **Size the pool to downstream capacity**, not arbitrarily high — 1000 threads hammering a DB with a 20-connection pool just creates contention and context-switching overhead.
- **Virtual threads (Java 21+)**: set `spring.threads.virtual.enabled=true`. Each request runs on a virtual thread, so blocking calls park cheaply (no OS thread held). This dramatically raises concurrency ceilings for blocking code. Caveats: avoid `synchronized` blocks around blocking I/O (pins the carrier thread; prefer `ReentrantLock`), and pooling becomes less relevant for the threads themselves (but DB connection pools still matter).
- **Jackson cost**: serialization is CPU-bound and often the hottest path for chatty JSON APIs. Reuse a single configured `ObjectMapper` (Spring does); prefer `record`s/immutable DTOs; avoid serializing entities directly (lazy-loading + N+1 + over-exposure). Consider `@JsonView` or projections to trim payloads.
- **Avoid `@EnableWebMvc`** in Boot unless you intend to take full manual control — it switches off Boot's sensible auto-config.
- **Connection/keep-alive tuning**: `server.tomcat.keep-alive-timeout`, `server.tomcat.max-keep-alive-requests`. HTTP/2 (`server.http2.enabled=true`) reduces connection overhead.
- **Response compression**: `server.compression.enabled=true` for text/JSON (set `min-response-size`).

### 6.2 Correctness & concurrency

- **Controllers and singletons**: by default Spring beans are **singletons** — one controller instance serves all threads concurrently. **Never store request state in instance fields.** Use local variables and method params (the resolved arguments) which are per-invocation.
- **Idempotency**: design `PUT`/`DELETE` to be idempotent; protect `POST` against duplicates with an idempotency key when the operation is money/inventory-sensitive.
- **Validation ordering**: deserialization happens before validation. Malformed JSON → `HttpMessageNotReadableException` (400) before any `@Valid` runs.
- **`BindingResult` placement**: when you want to handle validation errors in-method, the `BindingResult`/`Errors` param must come *immediately after* the validated object, or Spring throws instead.

### 6.3 Security

- **Spring Security runs as a filter** (`springSecurityFilterChain`) before the DispatcherServlet — so authn/authz happen before your controller. Method security (`@PreAuthorize`) runs via AOP at the service layer.
- **CSRF**: relevant for cookie/session-based browser apps; for stateless token (Bearer/JWT) REST APIs it is typically disabled because there is no ambient credential to forge. Be deliberate.
- **CORS**: configure via `WebMvcConfigurer.addCorsMappings` or `@CrossOrigin`; for Security-protected apps, CORS must also be permitted in the security filter chain (`http.cors()`), since the security filter runs first and OPTIONS preflight requests must pass.
- **Mass assignment**: do not bind directly to JPA entities from request bodies (an attacker can set fields you didn't intend, e.g. `role`, `isAdmin`). Bind to a DTO with only the fields you allow; or restrict with `@InitBinder` `setAllowedFields`.
- **Information leakage in errors**: never return stack traces or internal messages to clients. Map internals to generic 500 `ProblemDetail`; log the details server-side with the correlation ID.
- **Request size limits**: cap `spring.servlet.multipart.max-file-size` / `max-request-size` and consider a max payload filter to mitigate DoS via huge bodies.
- **RFD / path-extension content negotiation** is disabled by default in modern Spring — keep it that way.

### 6.4 Observability

- **Spring Boot Actuator + Micrometer**: `/actuator/metrics`, `/actuator/health`. The `http.server.requests` timer (tags: `uri`, `method`, `status`, `outcome`, `exception`) is the single most useful metric — gives latency percentiles and error rates per endpoint. Watch URI cardinality (use templated URIs like `/orders/{id}`, never raw IDs, to avoid metric explosion).
- **Distributed tracing**: Micrometer Tracing (Brave/OpenTelemetry) propagates trace/span IDs across services; correlation IDs in MDC tie logs together.
- **Structured logging**: put `correlationId`/`traceId` in the MDC (see §5.6) and the log pattern.
- **Access logs**: enable Tomcat access log (`server.tomcat.accesslog.enabled=true`) for an auditable request record independent of app logging.

### 6.5 Cost

- Blocking stack with right-sized pools is cheap and predictable for typical request rates. The main cost levers: thread count (memory ~1MB/platform thread stack — virtual threads remove this), JSON CPU, and downstream calls. Don't over-provision threads "just in case"; measure.

### 6.6 Testing

- **`@WebMvcTest`** — slices the context to just the web layer (controllers, advice, converters, no services/repositories), wiring **MockMvc**. Mock collaborators with `@MockBean`. Fast.
- **MockMvc** — drives the DispatcherServlet *without* a real network/servlet container:
  ```java
  @WebMvcTest(OrderController.class)
  class OrderControllerTest {
    @Autowired MockMvc mvc;
    @MockBean OrderService service;
    @Test void createReturns201() throws Exception {
      when(service.create(any())).thenReturn(new Order(99L, "ABC", 2));
      mvc.perform(post("/api/v1/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"sku":"ABC","quantity":2,"currency":"USD"}"""))
         .andExpect(status().isCreated())
         .andExpect(header().exists("Location"))
         .andExpect(jsonPath("$.id").value(99));
    }
  }
  ```
- **`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`/`WebTestClient`** — full integration with a real embedded server, real serialization over the wire.
- **`MockMvcTester`** (Spring 6.2) — fluent AssertJ-based MockMvc.
- Test validation paths, error mappings, and content negotiation explicitly — these are easy to break silently.

### 6.7 Production hardening checklist

- Graceful shutdown: `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase`.
- Timeouts everywhere: connection, read, and downstream client timeouts (a missing client read timeout is the classic cause of thread-pool exhaustion cascades).
- Consistent error contract (`ProblemDetail`) documented and versioned.
- API versioning strategy decided up front (`/v1` path, or media-type versioning).
- Idempotency + retries coordinated with clients.
- Rate limiting / payload caps at the edge (gateway or filter).
- Health checks that reflect real readiness (DB reachable), not just liveness.

### 6.8 Anti-patterns

- Returning JPA entities directly (lazy-init exceptions, N+1, over-exposure, coupling API to schema). **Always map to DTOs.**
- Business logic in controllers. Keep controllers thin: bind → delegate → map response.
- Catching exceptions in every controller method. Centralize in `@ControllerAdvice`.
- Using `@Autowired` field injection (hard to test, hides dependencies). Prefer constructor injection.
- Mutable shared state in singleton controllers.
- `@EnableWebMvc` in a Boot app (disables auto-config) unless intentional.
- Returning `200 OK` for everything and signaling errors in the body. Use HTTP status codes.
- Unbounded query results (no paging) — memory/latency landmine.

---

## 7. Advanced topics & deep internals

### 7.1 Path matching: `AntPathMatcher` vs `PathPattern`

Spring 5.3+ introduced **`PathPattern`** (parsed, more efficient, used by default in WebFlux and now the default in MVC for most cases) replacing the older string-based **`AntPathMatcher`**. `PathPattern` parses patterns into a tree at startup and matches faster; it changes some edge semantics (e.g., `**` only allowed at the end). Configure via `PathMatchConfigurer`. Boot property historically `spring.mvc.pathmatch.matching-strategy` (`path-pattern-parser` default, `ant-path-matcher` legacy). Trailing-slash matching (`/orders` vs `/orders/`) was **deprecated and then disabled by default** in Spring 6 — `/orders/` no longer matches `/orders` automatically; configure explicitly if needed (and prefer redirecting at the edge).

### 7.2 The matching algorithm and ambiguity

`RequestMappingHandlerMapping` collects all `RequestMappingInfo`s matching the request, then sorts by a composite comparator: path specificity, then HTTP method, params, headers, then `consumes`/`produces` media-type specificity. If two equally specific mappings remain → `IllegalStateException: Ambiguous handler methods`. Producible media types interact with the `Accept` header: a 406 (`HttpMediaTypeNotAcceptableException`) results when no producible type matches `Accept`; a 415 (`HttpMediaTypeNotSupportedException`) when the body's `Content-Type` matches no `consumes`.

### 7.3 `ConversionService`, `Formatter`, `PropertyEditor`

String → typed conversion of path/query params goes through the `ConversionService` (a registry of `Converter`/`GenericConverter`) and `Formatter`s (locale-aware parse/print, e.g., dates, currencies). `@DateTimeFormat` and `@NumberFormat` annotate fields/params for formatting. Legacy `PropertyEditor`s still work via `@InitBinder` `registerCustomEditor`. Register custom `Formatter`s in `WebMvcConfigurer.addFormatters`.

### 7.4 Jackson deep cuts

- **`@JsonView`**: define view marker classes and annotate fields; controllers select a view (`@JsonView(Views.Summary.class)`) to serialize subsets — useful for list vs detail payloads from one DTO.
- **`@JsonProperty`, `@JsonIgnore`, `@JsonInclude(NON_NULL)`, `@JsonFormat`** for shaping.
- **Polymorphic types**: `@JsonTypeInfo`/`@JsonSubTypes` for type discriminators — security-sensitive (default typing is a known deserialization-gadget risk; never enable global default typing on untrusted input).
- **`ObjectMapper` config via Boot**: `spring.jackson.default-property-inclusion=non_null`, `spring.jackson.serialization.*`, `spring.jackson.deserialization.fail-on-unknown-properties=false` (often set true for strict APIs). Register a `Jackson2ObjectMapperBuilderCustomizer` for programmatic control. Add the `jackson-datatype-jsr310` module (auto-registered by Boot) for `java.time` types; `spring.jackson.serialization.write-dates-as-timestamps=false` to emit ISO-8601 strings.
- **Streaming for huge payloads**: for very large responses, write to the `OutputStream` with Jackson's streaming API or use `StreamingResponseBody` to avoid buffering the whole document in memory.

### 7.5 Filters vs Interceptors vs AOP — the precise distinction

- **Filter** (Servlet API): outermost, wraps `DispatcherServlet`. Sees the raw request/response and *every* request (even those that 404 before any handler). Can wrap/replace the request/response (e.g., caching the body). Runs even when no controller matches. Ordered by `@Order` / `FilterRegistrationBean`.
- **HandlerInterceptor** (Spring MVC): inner, wraps the *handler* invocation. Has access to the resolved `handler` and (in `postHandle`) the `ModelAndView`. Does *not* run for unmatched requests. Three hooks: `preHandle`, `postHandle`, `afterCompletion`.
- **AOP advice**: wraps a bean method call (any layer). No HTTP awareness. This is where `@Transactional`, `@Retryable`, `@Cacheable`, `@PreAuthorize` operate. Order relative to the others: Filter → Interceptor.preHandle → (AOP around controller method, if proxied) → controller → ReturnValueHandler → Interceptor.postHandle → render → Interceptor.afterCompletion → Filter unwind.

Note: controllers are not always AOP-proxied; cross-cutting concerns on controller methods can be subtle. Prefer placing transactional/business AOP at the service layer.

### 7.6 Functional endpoints (`RouterFunction`) in MVC

Spring MVC also supports a **functional, programmatic** routing model (`MVC.fn`) mirroring WebFlux's:

```java
@Bean
RouterFunction<ServerResponse> routes(OrderHandler h) {
    return route()
        .GET("/api/orders/{id}", h::get)
        .POST("/api/orders", h::create)
        .build();
}
```

It runs through `RouterFunctionMapping` + `HandlerFunctionAdapter` on the same DispatcherServlet. Useful for dynamic/composed routing; less common than annotations.

### 7.7 Async internals and pitfalls

- `spring.mvc.async.request-timeout` bounds async completion; on timeout an `AsyncRequestTimeoutException` (503 by default) is raised.
- Thread locals (Security context, MDC, request attributes) are *not* automatically propagated to the async executor thread — use `DelegatingSecurityContextExecutor`, Micrometer `ContextSnapshot`, or task decorators (`TaskDecorator`) to copy context.
- Streaming responses bypass the normal `ModelAndView` rendering; backpressure is the client's TCP window.

### 7.8 HTTP caching and conditional requests

- `ResponseEntity` + `ETag`/`Last-Modified`: use `ShallowEtagHeaderFilter` (auto-computes ETag from response body, supports `304 Not Modified`) or set headers manually with `CacheControl`:
  ```java
  return ResponseEntity.ok()
      .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
      .eTag(order.version())
      .body(dto);
  ```
- `request.checkNotModified(eTag)` lets the controller short-circuit to 304.

### 7.9 `@ControllerAdvice` scoping and ordering

`@ControllerAdvice` can be scoped by `basePackages`, `assignableTypes`, or `annotations` (e.g., apply one advice only to admin controllers). Multiple advices are ordered by `@Order`/`Ordered`; the most specific `@ExceptionHandler` across all in-scope advices wins. A common pattern: one global advice for the standard error contract + a narrowly scoped advice for a bounded context.

### 7.10 Request/response body manipulation: `@RequestBodyAdvice` / `@ResponseBodyAdvice`

These advise the conversion process — e.g., decrypt request bodies, wrap all responses in an envelope, or add HATEOAS links. `ResponseBodyAdvice.beforeBodyWrite` runs after the controller returns and before the converter writes — a clean hook for cross-cutting body transforms without touching every controller.

### 7.11 Virtual threads, structured concurrency, and the future

With `spring.threads.virtual.enabled=true`, Boot configures Tomcat to use a virtual-thread-per-request executor and switches `@Async`/`ThreadPoolTaskExecutor` defaults. This makes the blocking MVC model scale to very high concurrency without a reactive rewrite, at the cost of needing to avoid thread-pinning constructs. For many teams this is now the recommended path over WebFlux for blocking workloads.

---

## 8. Tradeoffs & decision frameworks

### 8.1 Spring MVC vs WebFlux

| Dimension | Spring MVC (servlet) | Spring WebFlux (reactive) |
|---|---|---|
| Programming model | Imperative, blocking | Reactive (`Mono`/`Flux`), non-blocking |
| Concurrency model | thread-per-request (or virtual threads) | event loop, few threads |
| I/O | blocking (JDBC ok) | must be non-blocking end-to-end (R2DBC, reactive clients) |
| Learning curve | low | high |
| Ecosystem maturity | very mature | mature but more constrained |
| Best for | typical CRUD/REST, blocking deps | massive concurrency, streaming, non-blocking stack |
| Debuggability | easy (linear stack traces) | harder (assembly vs execution stacks) |

**Use MVC when**: blocking dependencies (JDBC, blocking SDKs), team familiarity, standard request rates; especially with virtual threads. **Avoid MVC / prefer WebFlux when**: you need to handle tens of thousands of concurrent long-lived/streaming connections with a fully non-blocking stack and you cannot use virtual threads.

### 8.2 Filter vs Interceptor vs AOP — when to use which

| Need | Choose |
|---|---|
| Affect all requests incl. unmatched/static, wrap req/resp, security | **Filter** |
| Logic tied to matched handler, access to `ModelAndView`, per-path | **Interceptor** |
| Business/service concern, transactions, retries, method-level security | **AOP** |

### 8.3 Error mechanisms

| Mechanism | Use when |
|---|---|
| `ResponseStatusException` | quick, ad-hoc status in a controller without a custom exception |
| `@ResponseStatus` on exception | a domain exception always maps to one status |
| `@ExceptionHandler` (local) | one controller needs special handling |
| `@RestControllerAdvice` + `ProblemDetail` | application-wide, standardized error contract (recommended default) |

### 8.4 DTO mapping approaches

| Approach | Pros | Cons |
|---|---|---|
| Manual (`OrderDto.from(...)`) | explicit, no magic, fast | boilerplate |
| MapStruct (compile-time) | fast, type-safe, generated | annotation processor setup |
| ModelMapper (reflection) | terse | runtime cost, less type-safe |

Recommendation: manual or MapStruct for production; avoid reflection mappers on hot paths.

### 8.5 API versioning

| Strategy | Pros | Cons |
|---|---|---|
| URI path (`/v1/...`) | obvious, cache-friendly, easy routing | "version in the URL" purist objection |
| Header / media-type (`Accept: application/vnd.x.v2+json`) | RESTful, URL stable | harder to test/debug, less visible |
| Query param (`?version=2`) | simple | pollutes URLs, weak caching |

URI versioning is the pragmatic default for most teams.

---

## 9. Failure modes & debugging

### 9.1 Thread-pool exhaustion (the classic outage)

**Symptom**: requests time out / queue; `http.server.requests` latency spikes; Tomcat `busyThreads` pegged at `max`; new requests rejected (`accept-count` exceeded → connection refused).
**Cause**: a slow or hung downstream (DB, external API) with no client timeout holds all 200 worker threads.
**Diagnose**: thread dump (`jstack <pid>` or Actuator `/actuator/threaddump`) → many threads blocked in the same downstream call. Actuator metrics `tomcat.threads.busy`, `tomcat.threads.config.max`.
**Fix**: set aggressive client read/connect timeouts; add circuit breakers (Resilience4j); bulkhead/limit concurrency to the slow dependency; consider virtual threads to decouple thread count from blocking. **Never** just raise `threads.max` blindly.

### 9.2 415 Unsupported Media Type

**Symptom**: `POST` returns 415. **Cause**: missing/incorrect `Content-Type` header (must be `application/json` for `@RequestBody` JSON), or no converter for the `consumes` type. **Diagnose**: check request `Content-Type`; check `consumes` on the mapping. **Fix**: send correct header / relax `consumes`.

### 9.3 406 Not Acceptable

**Cause**: `Accept` header requests a type the endpoint can't `produce`. **Fix**: align `Accept` and `produces`; add the converter (e.g., XML module).

### 9.4 400 with HttpMessageNotReadableException

**Cause**: malformed JSON, type mismatch (string where number expected), or `fail-on-unknown-properties=true` with extra fields. **Diagnose**: the exception's cause names the offending field/line. **Fix**: correct payload, or configure Jackson leniency deliberately.

### 9.5 Validation not firing

**Cause**: `@Valid`/`@Validated` missing; `spring-boot-starter-validation` not on classpath (Hibernate Validator absent); validating method params without `@Validated` on the class. **Fix**: add the starter; annotate correctly.

### 9.6 LazyInitializationException in the response

**Cause**: returning a JPA entity; Jackson touches a lazy association after the transaction/session closed. **Fix**: map to DTOs inside the transaction; never serialize entities. (This is the single most common Spring+Hibernate web bug.)

### 9.7 CORS errors in the browser

**Symptom**: browser console "blocked by CORS policy"; preflight `OPTIONS` 403. **Cause**: CORS not configured, or Spring Security blocking before MVC CORS runs. **Fix**: configure `addCorsMappings`/`@CrossOrigin` AND enable `http.cors()` in the security chain so preflight passes.

### 9.8 404 for an endpoint you "know" exists

**Causes**: wrong base path/`context-path`; controller not component-scanned (outside the `@SpringBootApplication` package tree); ambiguous/overridden mapping; trailing-slash semantics changed in Spring 6. **Diagnose**: enable `logging.level.org.springframework.web=DEBUG` to see the mapped handlers at startup, or hit `/actuator/mappings`. **Fix**: correct package/path; add explicit slash handling if required.

### 9.9 Ambiguous mapping at startup

**Symptom**: app fails to start with `Ambiguous mapping`. **Cause**: two methods map to the same path+method+conditions. **Fix**: differentiate by `produces`/`consumes`/`params`, or merge.

### 9.10 Memory blowups on large payloads

**Cause**: buffering whole request/response in memory (uploads, big JSON). **Fix**: stream (`InputStream`, `StreamingResponseBody`); set multipart and request size caps; paginate.

### 9.11 Lost MDC/Security context on async

**Cause**: thread-local context not propagated to async/executor threads. **Symptom**: logs missing correlation IDs; `SecurityContext` null in async handler. **Fix**: `TaskDecorator` copying MDC, `DelegatingSecurityContextAsyncTaskExecutor`, Micrometer context propagation.

### 9.12 Real-world incident pattern

A canonical postmortem: a third-party payment API slowed from 50ms to 8s during an incident. The Spring service had no read timeout on its HTTP client. Within seconds all 200 Tomcat threads were blocked awaiting payment responses; health checks (also served by the same pool) timed out; the load balancer marked the instance unhealthy; the remaining instances inherited the load and fell over in a cascade. **Lessons**: per-dependency timeouts, circuit breakers, separate the health-check path/pool, and prefer virtual threads or bulkheads so one slow dependency can't consume the whole capacity.

---

## 10. Interview drill

**Q1. Walk me through the full DispatcherServlet request lifecycle.**
*Model answer*: Request → filter chain → `DispatcherServlet.doDispatch`: multipart resolution; `HandlerMapping` returns a `HandlerExecutionChain` (handler + interceptors); `HandlerAdapter` selected; interceptor `preHandle`; `RequestMappingHandlerAdapter` resolves arguments (argument resolvers + message converters), invokes the controller, handles the return value (writes body via converter for `@ResponseBody` or builds a `ModelAndView`); interceptor `postHandle`; view rendering (skipped for `@ResponseBody`); exception resolvers on error; interceptor `afterCompletion`; filters unwind.
- *Probe: Where exactly is JSON serialized?* In the return-value handler (`RequestResponseBodyMethodProcessor`), via the selected `HttpMessageConverter` (Jackson), before `postHandle`/render; it sets `requestHandled=true` so no view rendering occurs.
- *Probe: What picks the converter?* Content negotiation: the `Accept` header intersected with `produces` and the converters' `canWrite`.
- *Probe: What if two mappings match?* Best-match by specificity; exact ambiguity → `IllegalStateException`.

**Q2. Difference between `@Controller` and `@RestController`?**
*Model*: `@RestController` = `@Controller` + `@ResponseBody`; every method's return value is serialized to the body instead of being treated as a view name.
- *Probe: Can a `@Controller` return JSON?* Yes, with `@ResponseBody` per method.
- *Probe: When still use `@Controller`?* Server-side rendered views (Thymeleaf/JSP).

**Q3. How does `@RequestBody` deserialization work, and how do you validate it?**
*Model*: `RequestResponseBodyMethodProcessor` reads the body stream, picks a converter by `Content-Type` (Jackson for JSON), deserializes into the parameter type; `@Valid` then runs Bean Validation; violations → `MethodArgumentNotValidException` (400).
- *Probe: Malformed JSON vs validation failure?* Malformed → `HttpMessageNotReadableException` (400) before validation; constraint violation → `MethodArgumentNotValidException`.
- *Probe: Where do you centralize the error response?* `@RestControllerAdvice` overriding `handleMethodArgumentNotValid` to emit `ProblemDetail`.

**Q4. Filter vs Interceptor vs AOP — when each?** (senior-signal)
*Model*: Filter = servlet-level, wraps DispatcherServlet, sees all requests incl. unmatched, can wrap req/resp — security, CORS, logging. Interceptor = Spring MVC, wraps the matched handler, sees handler/ModelAndView — handler-tied concerns. AOP = method-level on any bean, no HTTP awareness — transactions, retries, method security.
- *Probe: Why is Spring Security a filter, not an interceptor?* It must run before dispatch, guard all requests (including ones that wouldn't match a handler), and integrate at the servlet boundary.
- *Probe: Interceptor returns false in preHandle — what happens?* Dispatch short-circuits; controller doesn't run; `afterCompletion` of already-run interceptors fires.

**Q5. Design the error-handling strategy for a REST API.** (senior-signal)
*Model*: Standardize on `ProblemDetail` (RFC 9457). Use `@RestControllerAdvice` extending `ResponseEntityExceptionHandler` for framework exceptions; map domain exceptions to status + `ProblemDetail`; include a stable machine-readable `type` URI, a `title`, a `detail`, and extensions (field errors, correlation ID). Never leak internals; log full detail server-side with the correlation ID; document the contract; version it.
- *Probe: 400 vs 422?* 400 = malformed/unparseable; 422 = well-formed but semantically invalid (some teams use it for validation failures — be consistent).
- *Probe: How do you keep error responses consistent across many services?* Shared library/advice + a published problem-type registry.

**Q6. How does the thread model affect scalability, and what changed with virtual threads?** (senior-signal)
*Model*: Thread-per-request; each in-flight request holds a Tomcat thread; blocking I/O caps concurrency at the pool size (~200 default). Over-sizing the pool harms via contention. Virtual threads (Java 21+, `spring.threads.virtual.enabled=true`) let blocking calls park cheaply, raising concurrency without a reactive rewrite — provided you avoid pinning (`synchronized` over blocking I/O).
- *Probe: When still choose WebFlux?* Fully non-blocking stack needed, streaming, extreme concurrency with constrained resources, or when downstream is already reactive.
- *Probe: How size the pool?* To downstream capacity (DB connections, dependency limits), measured under load.

**Q7. Explain content negotiation in Spring MVC.**
*Model*: The `ContentNegotiationManager` determines response media type, by default from the `Accept` header, intersected with the endpoint's `produces` and converter capabilities. Optionally a request parameter (`?format=`) strategy. Path-extension is disabled by default (security). 406 if nothing matches `Accept`; 415 if request `Content-Type` matches no `consumes`.
- *Probe: Serve JSON and XML from one method?* `produces={JSON, XML}` + Jackson XML module; client's `Accept` selects.
- *Probe: Why is path-extension off?* RFD/content-sniffing security concerns.

**Q8. What's wrong with returning JPA entities from controllers?**
*Model*: `LazyInitializationException` when serializing lazy associations after the session closes; N+1 queries; over-exposure of internal fields; mass-assignment risk on input; tight coupling of API to schema. Use DTOs mapped within the transaction.
- *Probe: Mass assignment?* Binding request bodies straight to entities lets clients set fields you didn't intend (`role`). Use input DTOs / `setAllowedFields`.

**Q9. How do `@ControllerAdvice` and `@ExceptionHandler` resolve which handler runs?**
*Model*: `ExceptionHandlerExceptionResolver` searches the controller's own `@ExceptionHandler`s first, then in-scope `@ControllerAdvice` beans; the most specific exception type match wins; advices ordered by `@Order`.
- *Probe: Can you scope an advice?* Yes — `basePackages`, `assignableTypes`, `annotations`.

**Q10. How would you test a controller without starting a server?**
*Model*: `@WebMvcTest` + `MockMvc` drives the DispatcherServlet in-process; mock collaborators with `@MockBean`; assert status, headers, JSON body via `jsonPath`. For full wire integration, `@SpringBootTest(RANDOM_PORT)` + `WebTestClient`/`TestRestTemplate`.
- *Probe: Does MockMvc exercise filters?* It can be configured to include filters/Security; by default it tests the MVC layer.

**Q11. What's the difference between `PUT` and `PATCH`, and how do you implement PATCH cleanly?** (senior-signal)
*Model*: `PUT` fully replaces the resource (idempotent); `PATCH` partially updates. Clean PATCH: JSON Merge Patch (RFC 7386) or JSON Patch (RFC 6902); accept a partial representation, apply only present fields (careful distinguishing "field absent" from "field set to null" — use `Optional`/`JsonNode`/`JsonNullable`).
- *Probe: Pitfall with `null`?* Naive binding can't tell "omit" from "set null"; use a patch document or wrapper types.

**Q12. How do you propagate a correlation ID and security context across async boundaries?**
*Model*: Set correlation ID in a Filter into MDC; for async executors use a `TaskDecorator` to copy MDC and `DelegatingSecurityContextExecutor` (or Micrometer context propagation) so the executor thread inherits context; clear MDC in `finally`.

---

## 11. Glossary

- **AOP (Aspect-Oriented Programming)** — modularizing cross-cutting concerns (logging, transactions) as "aspects" applied around method calls via proxies.
- **ApplicationContext** — Spring's IoC container holding and wiring beans.
- **Argument resolver (`HandlerMethodArgumentResolver`)** — strategy that produces a controller method argument from the request.
- **Bean** — an object managed by the Spring container.
- **Bean Validation (Jakarta Validation, JSR 380)** — declarative constraint annotations; Hibernate Validator is the reference implementation.
- **BindingResult / Errors** — holds validation/binding errors for a bound object.
- **Circuit breaker** — a resilience pattern that stops calling a failing dependency after a threshold, failing fast.
- **CORS (Cross-Origin Resource Sharing)** — browser mechanism allowing a page from one origin to call an API on another, governed by response headers and a preflight `OPTIONS`.
- **Content negotiation** — choosing the response media type from `Accept`/config.
- **`ContentNegotiationManager`** — Spring component implementing the negotiation strategies.
- **Controller** — class handling web requests (`@Controller`/`@RestController`).
- **`@ControllerAdvice` / `@RestControllerAdvice`** — beans applying `@ExceptionHandler`/`@InitBinder`/`@ModelAttribute` across controllers.
- **`ConversionService`** — registry converting between types (e.g., String→Long for path vars).
- **DI (Dependency Injection)** — providing a bean's collaborators externally rather than constructing them internally.
- **`DeferredResult`** — async return type completed from another thread.
- **`DispatcherServlet`** — the front-controller servlet orchestrating the MVC pipeline.
- **DTO (Data Transfer Object)** — a class shaped for API input/output, decoupled from persistence entities.
- **`@ExceptionHandler`** — method that handles exceptions thrown by controllers.
- **Filter (Servlet)** — container-level interceptor wrapping the servlet; forms a chain.
- **Front controller** — single entry point for all requests.
- **`HandlerAdapter`** — invokes a handler of a given type; `RequestMappingHandlerAdapter` for annotated methods.
- **`HandlerExecutionChain`** — handler plus its applicable interceptors.
- **`HandlerInterceptor`** — Spring MVC hook around handler invocation (`preHandle`/`postHandle`/`afterCompletion`).
- **`HandlerMapping`** — maps requests to handlers; `RequestMappingHandlerMapping` for annotations.
- **`HandlerMethod`** — (bean, reflective method) wrapper for a controller method.
- **HATEOAS** — REST constraint where responses include links to related actions/resources.
- **Hibernate Validator** — reference implementation of Bean Validation (unrelated to Hibernate ORM).
- **HTTP/2** — binary, multiplexed HTTP version reducing connection overhead.
- **`HttpEntity` / `RequestEntity` / `ResponseEntity`** — objects carrying status/headers/body.
- **`HttpMessageConverter`** — converts between Java objects and HTTP bodies (Jackson for JSON).
- **Idempotent** — repeating the operation yields the same effect as one application.
- **IoC (Inversion of Control)** — framework controls object creation/wiring.
- **Jackson** — the default JSON (de)serialization library in Spring.
- **Jakarta EE** — the renamed Java EE; `jakarta.*` namespace used in Spring 6 / Boot 3.
- **MDC (Mapped Diagnostic Context)** — per-thread key/value map for enriching log lines.
- **`@ModelAttribute`** — binds request data into an object / pre-populates the model.
- **`ModelAndView`** — container for model data plus a view name.
- **MockMvc** — test utility driving the DispatcherServlet without a server.
- **Multipart** — `multipart/form-data` encoding for file uploads.
- **Micrometer** — metrics facade; `http.server.requests` is the key web metric.
- **N+1 query** — performance anti-pattern: one query plus one per row for associations.
- **`OncePerRequestFilter`** — filter base class guaranteeing single execution per request.
- **`PathPattern` / `AntPathMatcher`** — URL pattern matchers (parsed vs string-based).
- **`@PathVariable`** — binds a URI template variable.
- **ProblemDetail (RFC 9457, formerly RFC 7807)** — standardized JSON error body.
- **REST (Representational State Transfer)** — architectural style for HTTP APIs.
- **`@RequestBody` / `@ResponseBody`** — bind the HTTP body to/from a Java object.
- **`@RequestMapping` and shortcuts** — map requests to handlers by path/method/etc.
- **`@RequestParam` / `@RequestHeader` / `@CookieValue`** — bind query params/headers/cookies.
- **`ResponseStatusException`** — throwable carrying an HTTP status.
- **Return-value handler (`HandlerMethodReturnValueHandler`)** — turns a method's return value into a response.
- **`@RestController`** — `@Controller` + `@ResponseBody`.
- **Servlet / Servlet container** — Java HTTP handler / the server runtime hosting it (Tomcat).
- **`SseEmitter` / `ResponseBodyEmitter` / `StreamingResponseBody`** — streaming response types.
- **Spring Boot Actuator** — production endpoints for metrics/health/info.
- **Spring Security** — authn/authz framework integrating as a servlet filter.
- **Thread-per-request** — model where each request occupies one worker thread.
- **`@Valid` / `@Validated`** — trigger Bean Validation (the latter also supports groups/method validation).
- **`ViewResolver` / `View`** — resolve a view name into a renderer and render it.
- **Virtual threads (Project Loom)** — lightweight JVM threads (Java 21+) that park cheaply on blocking calls.
- **WebFlux** — Spring's reactive, non-blocking web framework (sibling to MVC).
- **`WebMvcConfigurer`** — interface to customize MVC without disabling Boot auto-config.

---

## 12. Cheat-sheet & self-test

### Cheat-sheet (one screen)

**Pipeline**: Filter → DispatcherServlet.doDispatch → HandlerMapping → HandlerAdapter → preHandle → [resolve args (converters/@Valid) → invoke → handle return (converter+negotiation)] → postHandle → render(skip if @ResponseBody) → [ExceptionResolvers on error] → afterCompletion → Filter unwind.

**Annotations**: `@RestController`=`@Controller`+`@ResponseBody`; verbs `@GetMapping`/`@PostMapping`/`@PutMapping`/`@PatchMapping`/`@DeleteMapping`; inputs `@PathVariable`/`@RequestParam`/`@RequestHeader`/`@RequestBody`/`@RequestPart`; `@Valid`/`@Validated`; errors `@ExceptionHandler`+`@RestControllerAdvice`+`ProblemDetail`.

**Key numbers/defaults**: Tomcat `threads.max=200`, `min-spare=10`, `max-connections=8192`, `accept-count=100`; `server.port=8080`; multipart file 1MB / request 10MB; DispatcherServlet path `/`; virtual threads off by default.

**Status codes**: 200/201(+Location)/204; 400(malformed/validation)/401/403/404/405/406(Accept)/409/415(Content-Type)/422/429; 500/502/503.

**Converters**: Jackson JSON default; add `jackson-dataformat-xml` for XML; `StringHttpMessageConverter`, `ByteArrayHttpMessageConverter`, `ResourceHttpMessageConverter`, `FormHttpMessageConverter`.

**Cross-cutting**: Filter (servlet, all requests, raw req/resp) ▸ Interceptor (handler-tied, ModelAndView) ▸ AOP (method-level, transactions/security).

**Decision rules**: blocking deps + normal load → MVC (add virtual threads for scale); fully non-blocking + extreme concurrency → WebFlux. Never serialize JPA entities → DTOs. Centralize errors → `@RestControllerAdvice`+`ProblemDetail`. Always set client timeouts + circuit breakers. Constructor injection. Thin controllers. Page everything.

**Negotiation**: header strategy default; `favor-parameter` opt-in (`?format=`); path-extension disabled (security). 406 vs 415: Accept vs Content-Type.

**Testing**: `@WebMvcTest`+MockMvc (slice) ; `@SpringBootTest(RANDOM_PORT)`+WebTestClient (full).

**Debug**: `logging.level.org.springframework.web=DEBUG`, `/actuator/mappings`, `/actuator/threaddump`, `http.server.requests` metric, `jstack`.

### Self-test (no answers)

1. Trace exactly where and how the JSON response body is written for a `@RestController` method returning a record — which component, in what step, and why is view rendering skipped?
2. You added `@Valid` to a `@RequestBody` but malformed JSON returns 400 *without* your field-level error map. Why, and where does that error originate vs a constraint violation?
3. A dependency slows to 8s and your service stops responding to health checks. Explain the failure chain with the default thread model, and give three fixes ranked by impact.
4. Distinguish a Filter, a HandlerInterceptor, and an AOP advice by what each can see and when it runs; give one concern that *only* a Filter can address.
5. Design the error contract for a multi-service REST platform using `ProblemDetail`. What goes in `type`, `title`, `detail`, and extensions, and how do you keep it consistent and non-leaky across services?
6. When would you enable virtual threads instead of migrating to WebFlux, and what code pattern must you avoid to keep them effective?
7. Explain 406 vs 415, the headers involved, and the `produces`/`consumes` conditions that trigger each.
