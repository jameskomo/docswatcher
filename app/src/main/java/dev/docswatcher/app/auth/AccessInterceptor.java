package dev.docswatcher.app.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The per-organisation and per-repository check for signed-in members.
 *
 * <p>It runs after routing, so it reads the path variables exactly as the handler will receive
 * them: the same decoded, parameter-stripped values, with no second parser to disagree with the
 * first. The owner passes straight through. A member reaches only handlers marked
 * {@link MemberAccess}, and only for scopes GitHub said they could see.
 *
 * <p>It also resolves {@link Viewer} handler arguments, so services filter organisation-wide
 * answers by the same viewer the check used.
 */
@Component
public class AccessInterceptor implements HandlerInterceptor, HandlerMethodArgumentResolver {

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    Viewer viewer = viewer(request);
    if (viewer == null) {
      // ApiTokenFilter sets a viewer on every API request it lets through. None here means the
      // filter and the router disagreed about what is an API request: refuse rather than guess.
      if (CorsUtils.isPreFlightRequest(request)) {
        return true;
      }
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return false;
    }
    if (viewer.seesEverything()) {
      return true;
    }
    if (viewer instanceof Viewer.Ingest) {
      // An ingest token posts one repository's telemetry and does nothing else.
      if (handler instanceof HandlerMethod method && method.hasMethodAnnotation(IngestAccess.class)) {
        return true;
      }
      response.sendError(HttpServletResponse.SC_FORBIDDEN);
      return false;
    }
    if (!(handler instanceof HandlerMethod method)) {
      boolean preflight = CorsUtils.isPreFlightRequest(request);
      if (!preflight) {
        response.sendError(HttpServletResponse.SC_FORBIDDEN);
      }
      return preflight;
    }
    MemberAccess access = method.getMethodAnnotation(MemberAccess.class);
    if (access == null) {
      access = method.getBeanType().getAnnotation(MemberAccess.class);
    }
    if (access == null) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN);
      return false;
    }
    @SuppressWarnings("unchecked")
    Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    vars = vars == null ? Map.of() : vars;

    if (vars.containsKey("login") && !viewer.canSeeOrg(vars.get("login"))) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return false;
    }
    if (vars.containsKey("repoId")) {
      long repoId;
      try {
        repoId = Long.parseLong(vars.get("repoId"));
      } catch (NumberFormatException e) {
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
        return false;
      }
      if (!viewer.canRead(repoId)) {
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
        return false;
      }
      if (access.value() == MemberAccess.Level.WRITE && !viewer.canWrite(repoId)) {
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Needs write access to the repository");
        return false;
      }
    } else if (access.value() == MemberAccess.Level.WRITE) {
      // With no repository in the path, an organisation-wide setting needs write on one of the
      // organisation's repositories; an action naming neither has nothing to check against.
      if (!vars.containsKey("login") || !viewer.canWriteOrg(vars.get("login"))) {
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Needs write access to a repository in the organisation");
        return false;
      }
    }
    return true;
  }

  static Viewer viewer(HttpServletRequest request) {
    return request.getAttribute(Viewer.ATTRIBUTE) instanceof Viewer v ? v : null;
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return Viewer.class.equals(parameter.getParameterType());
  }

  /** A handler taking a Viewer outside the filtered paths still gets one that sees nothing. */
  @Override
  public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
    HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
    Viewer viewer = request == null ? null : viewer(request);
    return viewer != null ? viewer : Viewer.Member.of(new UserSession(0, "", null, null, java.util.List.of(), null, null));
  }
}
