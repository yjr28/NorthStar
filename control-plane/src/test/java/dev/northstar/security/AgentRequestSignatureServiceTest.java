package dev.northstar.security;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AgentRequestSignatureServiceTest {
    @Test void acceptsValidSignatureOnceAndRejectsReplayAndTampering() throws Exception {
        Instant now=Instant.parse("2026-09-20T18:00:00Z");
        var service=new AgentRequestSignatureService(Clock.fixed(now,ZoneOffset.UTC));
        UUID host=UUID.randomUUID();String token="signature-test-agent-token-000001";String path="/api/v1/hosts/"+host+"/telemetry";String ts=now.toString();String nonce=UUID.randomUUID().toString();byte[] body="{\"cpuPercent\":1}".getBytes(StandardCharsets.UTF_8);
        String sig=sign(token,"POST",path,ts,nonce,body);
        assertThat(service.verify(host,token,"POST",path,body,ts,nonce,sig)).isTrue();
        assertThat(service.verify(host,token,"POST",path,body,ts,nonce,sig)).isFalse();
        String nonce2=UUID.randomUUID().toString();
        assertThat(service.verify(host,token,"POST",path,"tampered".getBytes(StandardCharsets.UTF_8),ts,nonce2,sign(token,"POST",path,ts,nonce2,body))).isFalse();
    }
    @Test void rejectsStaleTimestamp() throws Exception {
        Instant now=Instant.parse("2026-09-20T18:00:00Z");var service=new AgentRequestSignatureService(Clock.fixed(now,ZoneOffset.UTC));UUID host=UUID.randomUUID();String token="signature-test-agent-token-000001",path="/api/v1/hosts/"+host+"/telemetry",ts="2026-09-20T17:54:59Z",nonce=UUID.randomUUID().toString();byte[] body="{}".getBytes(StandardCharsets.UTF_8);assertThat(service.verify(host,token,"POST",path,body,ts,nonce,sign(token,"POST",path,ts,nonce,body))).isFalse();
    }
    private static String sign(String token,String method,String path,String ts,String nonce,byte[] body)throws Exception{String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));String canonical=method+"\n"+path+"\n"+ts+"\n"+nonce+"\n"+digest;Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));}
}
