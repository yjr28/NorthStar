package dev.northstar.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.northstar.observability.NorthStarMetrics;
import dev.northstar.repository.ControlPlaneRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AgentSignatureFilterTest {
    private final AgentSignatureFilter filter=new AgentSignatureFilter(mock(AgentCredentialService.class),mock(AgentRequestSignatureService.class),mock(ControlPlaneRepository.class),mock(NorthStarMetrics.class),true);

    @Test void protectsEveryAgentMutationRoute(){
        String host="123e4567-e89b-12d3-a456-426614174000";
        String command="123e4567-e89b-12d3-a456-426614174001";
        assertProtected("/api/v1/hosts/"+host+"/heartbeat");
        assertProtected("/api/v1/hosts/"+host+"/telemetry");
        assertProtected("/api/v1/hosts/"+host+"/commands/lease");
        assertProtected("/api/v1/hosts/"+host+"/credentials/rotate");
        assertProtected("/api/v1/commands/"+command+"/ack");
    }

    @Test void leavesOperatorAndBootstrapRoutesToTheirOwnSecurityLayers(){
        assertThat(filter.shouldNotFilter(request("POST","/api/v1/hosts"))).isTrue();
        assertThat(filter.shouldNotFilter(request("POST","/api/v1/commands"))).isTrue();
        assertThat(filter.shouldNotFilter(request("GET","/api/v1/hosts/123e4567-e89b-12d3-a456-426614174000/status"))).isTrue();
    }

    private void assertProtected(String path){assertThat(filter.shouldNotFilter(request("POST",path))).isFalse();}
    private static MockHttpServletRequest request(String method,String path){MockHttpServletRequest request=new MockHttpServletRequest(method,path);request.setRequestURI(path);return request;}
}
