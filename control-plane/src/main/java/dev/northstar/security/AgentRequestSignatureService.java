package dev.northstar.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class AgentRequestSignatureService {
    private static final Duration MAX_SKEW = Duration.ofMinutes(5);
    private final ConcurrentHashMap<String, Instant> seenNonces = new ConcurrentHashMap<>();
    private final Clock clock;

    public AgentRequestSignatureService(){this(Clock.systemUTC());}
    AgentRequestSignatureService(Clock clock){this.clock=clock;}

    public boolean verify(UUID hostId,String token,String method,String path,byte[] body,String timestamp,String nonce,String signature){
        if(token==null||timestamp==null||nonce==null||signature==null) return false;
        final Instant signedAt;
        try{signedAt=Instant.parse(timestamp);UUID.fromString(nonce);}catch(Exception e){return false;}
        Instant now=clock.instant();
        if(Duration.between(signedAt,now).abs().compareTo(MAX_SKEW)>0)return false;
        String bodyHash=sha256(body);
        String canonical=method+"\n"+path+"\n"+timestamp+"\n"+nonce+"\n"+bodyHash;
        String expected=hmac(token,canonical);
        if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),signature.toLowerCase().getBytes(StandardCharsets.US_ASCII)))return false;
        seenNonces.entrySet().removeIf(e->e.getValue().isBefore(now.minus(MAX_SKEW)));
        return seenNonces.putIfAbsent(hostId+":"+nonce,now)==null;
    }

    private static String sha256(byte[] body){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hmac(String token,String canonical){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
