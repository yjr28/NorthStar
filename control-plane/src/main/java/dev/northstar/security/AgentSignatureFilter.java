package dev.northstar.security;

import dev.northstar.observability.NorthStarMetrics;
import dev.northstar.repository.ControlPlaneRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AgentSignatureFilter extends OncePerRequestFilter {
    private static final Pattern HOST_MUTATION=Pattern.compile("^/api/v1/hosts/([0-9a-fA-F-]{36})/(?:heartbeat|telemetry|commands/lease|credentials/rotate)$");
    private static final Pattern COMMAND_ACK=Pattern.compile("^/api/v1/commands/([0-9a-fA-F-]{36})/ack$");
    private final AgentCredentialService credentials;
    private final AgentRequestSignatureService signatures;
    private final ControlPlaneRepository repo;
    private final NorthStarMetrics metrics;
    private final boolean required;
    public AgentSignatureFilter(AgentCredentialService credentials,AgentRequestSignatureService signatures,ControlPlaneRepository repo,NorthStarMetrics metrics,@Value("${northstar.security.require-agent-signatures:false}") boolean required){this.credentials=credentials;this.signatures=signatures;this.repo=repo;this.metrics=metrics;this.required=required;}

    @Override protected boolean shouldNotFilter(HttpServletRequest request){if(!required||!"POST".equals(request.getMethod()))return true;String path=request.getRequestURI();return !HOST_MUTATION.matcher(path).matches()&&!COMMAND_ACK.matcher(path).matches();}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        byte[] body=request.getInputStream().readAllBytes();
        Optional<UUID> hostId=resolveHost(request.getRequestURI());
        String token=request.getHeader("X-NorthStar-Agent-Token");
        boolean valid=hostId.isPresent()&&credentials.authenticate(hostId.get(),token)&&signatures.verify(hostId.get(),token,request.getMethod(),request.getRequestURI(),body,request.getHeader("X-NorthStar-Timestamp"),request.getHeader("X-NorthStar-Nonce"),request.getHeader("X-NorthStar-Signature"));
        if(!valid){metrics.agentAuthFailure();response.sendError(HttpServletResponse.SC_UNAUTHORIZED);return;}
        chain.doFilter(new CachedBodyRequest(request,body),response);
    }

    private Optional<UUID> resolveHost(String path){
        Matcher host=HOST_MUTATION.matcher(path);if(host.matches())return Optional.of(UUID.fromString(host.group(1)));
        Matcher ack=COMMAND_ACK.matcher(path);if(ack.matches())return repo.findCommand(UUID.fromString(ack.group(1))).map(command->command.hostId());
        return Optional.empty();
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper{
        private final byte[] body;CachedBodyRequest(HttpServletRequest request,byte[] body){super(request);this.body=body;}
        @Override public ServletInputStream getInputStream(){ByteArrayInputStream in=new ByteArrayInputStream(body);return new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){}};}
    }
}
