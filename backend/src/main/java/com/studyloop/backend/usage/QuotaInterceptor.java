package com.studyloop.backend.usage;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.UUID;

// Applies QuotaGuard to the endpoints that cost money, before the controller runs.
//
// A HandlerInterceptor rather than a servlet filter, which is what a rate limiter usually is:
// throwing from preHandle lands in DispatcherServlet's exception path, so the refusal comes back
// as the same RFC 7807 ProblemDetail as every other error in this app, from the same
// @RestControllerAdvice. A filter sits outside that and would have to serialize its own JSON,
// which is how an API ends up with one error shape for 403 and a different one for 429.
//
// preHandle also runs only on the initial dispatch. The SSE chat stream comes back through the
// container a second time as an ASYNC dispatch, which reaches afterConcurrentHandlingStarted
// instead — so a streamed answer is charged one token, not two.
public class QuotaInterceptor implements HandlerInterceptor {

    // Which allowance this registration spends from — see WebMvcConfig for the path lists.
    public enum Kind { AI, UPLOAD }

    private final QuotaGuard guard;
    private final Kind kind;
    // Path patterns can't distinguish GET /quizzes (a list, free) from POST /quizzes (generation,
    // a model call), so the method is part of the registration rather than checked by hand
    // against a table of paths inside.
    private final Set<String> methods;

    public QuotaInterceptor(QuotaGuard guard, Kind kind, Set<String> methods) {
        this.guard = guard;
        this.kind = kind;
        this.methods = methods;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!methods.contains(request.getMethod())) {
            return true;
        }
        // Set by AiUsageAttributionFilter, which runs in the security chain and therefore well
        // before any interceptor. An anonymous request has no allowance to spend and no way to
        // reach these endpoints either — authorization refuses it a moment later.
        UUID actorId = AiUsageContext.currentActor();
        if (kind == Kind.UPLOAD) {
            guard.checkUpload(actorId);
        } else {
            guard.checkAi(actorId);
        }
        return true;
    }
}
