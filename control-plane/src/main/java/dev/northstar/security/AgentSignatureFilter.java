package dev.northstar.security;

import dev.northstar.observability.NorthStarMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AgentSignatureFilter extends OncePerRequestFilter {
    private static final Pattern TELEMETRY=Pattern.compile("^/api/v1/hosts/([0-9a-fA-F-]{36})/telemetry$");
    private final AgentCredentialService credentials;
    private final AgentRequestSignatureService signatures;
    private final NorthStarMetrics metrics;
    private final boolean required;
    public AgentSignatureFilter(AgentCredentialService credentials,AgentRequestSignatureService signatures,NorthStarMetrics metrics,@Value("${northstar.security.require-agent-signatures:false}") boolean required){this.credentials=credentials;this.signatures=signatures;this.metrics=metrics;this.required=required;}

    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !required||!"POST".equals(request.getMethod())||!TELEMETRY.matcher(request.getRequestURI()).matches();}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        byte[] body=request.getInputStream().readAllBytes();
        Matcher matcher=TELEMETRY.matcher(request.getRequestURI());matcher.matches();UUID hostId=UUID.fromString(matcher.group(1));
        String token=request.getHeader("X-NorthStar-Agent-Token");
        boolean valid=credentials.authenticate(hostId,token)&&signatures.verify(hostId,token,request.getMethod(),request.getRequestURI(),body,request.getHeader("X-NorthStar-Timestamp"),request.getHeader("X-NorthStar-Nonce"),request.getHeader("X-NorthStar-Signature"));
        if(!valid){metrics.agentAuthFailure();response.sendError(HttpServletResponse.SC_UNAUTHORIZED);return;}
        chain.doFilter(new CachedBodyRequest(request,body),response);
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper{
        private final byte[] body;CachedBodyRequest(HttpServletRequest request,byte[] body){super(request);this.body=body;}
        @Override public ServletInputStream getInputStream(){ByteArrayInputStream in=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){}};}
    }
}
