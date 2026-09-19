package dev.northstar.security;

import dev.northstar.repository.ControlPlaneRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AgentCredentialService {
    private final ControlPlaneRepository repo;
    public AgentCredentialService(ControlPlaneRepository repo){this.repo=repo;}

    public void provision(UUID hostId,String token){repo.createCredential(hostId,hash(token),OffsetDateTime.now(ZoneOffset.UTC));}
    public boolean authenticate(UUID hostId,String token){
        if(token==null||token.isBlank()) return false;
        return repo.credentialHash(hostId).map(expected->MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),hash(token).getBytes(StandardCharsets.US_ASCII))).orElse(false);
    }
    public boolean rotate(UUID hostId,String currentToken,String newToken){
        if(!authenticate(hostId,currentToken)||newToken==null||newToken.length()<24) return false;
        return repo.rotateCredential(hostId,hash(newToken),OffsetDateTime.now(ZoneOffset.UTC))==1;
    }
    private static String hash(String token){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
