import com.google.common.io.Files;
import com.jayway.jsonpath.matchers.JsonPathMatchers;
import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.KubernetesListBuilder;
import io.fabric8.maven.core.config.ProcessorConfig;
import io.fabric8.maven.core.util.KubernetesResourceUtil;
import io.fabric8.maven.docker.config.BuildImageConfiguration;
import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.maven.enricher.api.EnricherContext;
import io.fabric8.maven.enricher.standard.DefaultControllerEnricher;
import mockit.Expectations;
import mockit.Mocked;
import mockit.integration.junit4.JMockit;
import org.apache.maven.project.MavenProject;
import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.workspace7.cloud.fabric8.IstioEnricher;

import java.util.Arrays;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

/**
 * TODO - add more test cases
 *
 * @author kameshs
 */
@RunWith(JMockit.class)
public class InstioEnricherTest {

    final String ISTIO_INIT_CONTAINERS = "[{\"args\":[\"-p\",\"15001\",\"-u\",\"1337\"],\"image\":\"docker.io/istio/init:0.1\",\"imagePullPolicy\":\"IfNotPresent\",\"name\":\"init\",\"securityContext\":{\"capabilities\":{\"add\":[\"NET_ADMIN\"]}}},{\"args\":[\"-c\",\"sysctl\",\"-w kernel.core_pattern=/tmp/core.%e.%p.%t \\\\u0026\\\\u0026 ulimit -c unlimited\\\\\",\"-1337\"],\"image\":\"alpine\",\"imagePullPolicy\":\"IfNotPresent\",\"name\":\"enable-core-dump\",\"securityContext\":{\"privileged\":true},\"command\":[\"/bin/sh\"]}]";
    @Mocked
    private EnricherContext context;

    @Mocked
    ImageConfiguration imageConfiguration;

    @Mocked
    MavenProject project;

    @Test
    public void checkEnrichDeployment() throws Exception {
        enrichAndAssert(1, 1);
    }

    protected void enrichAndAssert(int sizeOfObjects, int replicaCount) throws com.fasterxml.jackson.core.JsonProcessingException {


        // Setup a sample docker build configuration
        final BuildImageConfiguration buildConfig =
            new BuildImageConfiguration.Builder()
                .ports(Arrays.asList("8080"))
                .build();

        final TreeMap controllerConfig = new TreeMap();
        controllerConfig.put("replicaCount", String.valueOf(replicaCount));

        final TreeMap enricherConfig = new TreeMap();

        setupExpectations(buildConfig, controllerConfig, enricherConfig);
        KubernetesList list = enrichAndBuild();


        assertEquals(sizeOfObjects, list.getItems().size());

        String json = KubernetesResourceUtil.toJson(list.getItems().get(0));
        assertThat(json, JsonPathMatchers.isJson());
        assertThat(json, JsonPathMatchers.hasJsonPath("$.spec.template.spec.containers.length()", Matchers.equalTo(2)));
        assertIstioProxy(json, "proxy");
    }

    private void assertIstioProxy(String json, String name) {
        assertThat(json, JsonPathMatchers.hasJsonPath("$.spec.template.metadata.annotations['alpha.istio.io/sidecar']",
            Matchers.equalTo("injected")));

        assertThat(json, JsonPathMatchers.hasJsonPath("$.spec.template.metadata.annotations['alpha.istio.io/version']",
            Matchers.equalTo("jenkins@ubuntu-16-04-build-12ac793f80be71-0.1.6-dab2033")));

        assertThat(json, JsonPathMatchers.hasJsonPath("$.spec.template.metadata.annotations['pod.beta.kubernetes.io/init-containers']",
            Matchers.equalTo(ISTIO_INIT_CONTAINERS)));

        assertThat(json, JsonPathMatchers.hasJsonPath("$.spec.template.spec.containers[1].name",
            Matchers.equalTo(name)));
        assertThat(json, JsonPathMatchers.hasJsonPath("$.spec.template.spec.containers[1].image",
            Matchers.equalTo("docker.io/istio/proxy_debug:0.1")));

        assertThat(json, JsonPathMatchers.hasJsonPath("$.spec.template.spec.containers[1].securityContext.runAsUser",
            Matchers.equalTo(1337)));

        assertThat(json, JsonPathMatchers.hasJsonPath("$.spec.template.spec.containers[1].args[0]",
            Matchers.equalTo("proxy")));
        assertThat(json, JsonPathMatchers.hasJsonPath("$.spec.template.spec.containers[1].args[1]",
            Matchers.equalTo("sidecar")));
        assertThat(json, JsonPathMatchers.hasJsonPath("$.spec.template.spec.containers[1].args[2]",
            Matchers.equalTo("-v")));
        assertThat(json, JsonPathMatchers.hasJsonPath("$.spec.template.spec.containers[1].args[3]",
            Matchers.equalTo("2")));
    }

    private KubernetesList enrichAndBuild() {
        // Enrich
        DefaultControllerEnricher controllerEnricher = new DefaultControllerEnricher(context);
        IstioEnricher istioEnricher = new IstioEnricher(context);

        KubernetesListBuilder builder = new KubernetesListBuilder();
        controllerEnricher.addMissingResources(builder);
        istioEnricher.addMissingResources(builder);

        // Validate that the generated resource contains
        return builder.build();
    }

    protected void setupExpectations(final BuildImageConfiguration buildConfig,
                                     final TreeMap controllerConfig,
                                     final TreeMap enricherConfig) {
        new Expectations() {{

            project.getArtifactId();
            result = "fmp-istio-test";

            project.getBuild().getOutputDirectory();
            result = Files.createTempDir().getAbsolutePath();

            context.getProject();
            result = project;

            TreeMap<String, TreeMap> config = new TreeMap<>();
            config.put("fmp-controller", controllerConfig);
            config.put("fmp-istio-enricher", enricherConfig);

            context.getConfig();
            result = new ProcessorConfig(null, null, config);

            imageConfiguration.getBuildConfiguration();
            result = buildConfig;

            imageConfiguration.getName();
            result = "helloworld";

            context.getImages();
            result = Arrays.asList(imageConfiguration);
        }};
    }
}
