package org.workspace7.cloud.fabric8;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.DeploymentSpecBuilder;
import io.fabric8.maven.core.util.Configs;
import io.fabric8.maven.enricher.api.BaseEnricher;
import io.fabric8.maven.enricher.api.EnricherContext;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * This enricher takes care of adding <a href="https://isito.io">Istio</a> related enrichments to the Kubernetes Deployment
 * @author kameshs
 */
public class IstioEnricher extends BaseEnricher {

    final String ISTIO_INIT_CONTAINERS = "[{\"args\":[\"-p\",\"15001\",\"-u\",\"1337\"],\"image\":\"docker.io/istio/init:0.1\",\"imagePullPolicy\":\"IfNotPresent\"," +
        "\"name\":\"init\",\"securityContext\":{\"capabilities\":{\"add\":[\"NET_ADMIN\"]}}},{\"args\":[\"-c\",\"sysctl\n" +
        "          -w kernel.core_pattern=/tmp/core.%e.%p.%t \\u0026\\u0026 ulimit -c unlimited\"],\"command\":[\"/bin/sh\"]," +
        "\"image\":\"alpine\",\"imagePullPolicy\":\"IfNotPresent\",\"name\":\"enable-core-dump\",\"securityContext\":{\"privileged\":true}}]";

    // Available configuration keys
    private enum Config implements Configs.Key {
        enabled {{
            d = "yes";
        }},
        proxyName {{
            d = "proxy";
        }},
        proxyImage {{
            d = "docker.io/istio/proxy_debug:0.1";
        }},
        proxyArgs {{
            d = "proxy,sidecar,-v,2";
        }},
        pullPolicy {{
            d = "IfNotPresent";
        }};

        public String def() {
            return d;
        }

        protected String d;
    }

    public IstioEnricher(EnricherContext buildContext) {
        super(buildContext, "fmp-istio-enricher");
    }

    //TODO - need to check if istio proxy side car is already there
    //TODO - adding init-containers to template spec
    @Override
    public void addMissingResources(KubernetesListBuilder builder) {
        String[] proxyArgs = getConfig(Config.proxyArgs).split(",");
        for (int i = 0; i < proxyArgs.length; i++) {
            proxyArgs[i] = StringUtils.trim(proxyArgs[i]);
        }
        builder.accept(new TypedVisitor<DeploymentSpecBuilder>() {
            public void visit(DeploymentSpecBuilder deploymentSpecBuilder) {
                if ("yes".equalsIgnoreCase(getConfig(Config.enabled))) {
                    log.info("Adding Istio proxy");
                    deploymentSpecBuilder
                        .editOrNewTemplate()
                        .editOrNewMetadata()
                        .addToAnnotations("alpha.istio.io/sidecar", "injected")
                        .addToAnnotations("alpha.istio.io/version", "jenkins@ubuntu-16-04-build-12ac793f80be71-0.1.6-dab2033")
                        .addToAnnotations("pod.beta.kubernetes.io/init-containers", ISTIO_INIT_CONTAINERS)
                        .endMetadata()
                        .editOrNewSpec()
                        .addNewContainer()
                        .withName(getConfig(Config.proxyName))
                        .withImage(getConfig(Config.proxyImage))
                        .withImagePullPolicy(getConfig(Config.pullPolicy))
                        .withArgs(proxyArgs)
                        .withEnv(proxyEnvVars())
                        .withSecurityContext(new SecurityContextBuilder()
                            .withRunAsUser(1337l)
                            .build())
                        .endContainer()
                        .endSpec()
                        .endTemplate();
                }
            }
        });
    }

    /**
     * The method to return list of environment variables that will be needed for Istio proxy
     * @return - list of {@link EnvVar}
     */
    private List<EnvVar> proxyEnvVars() {
        List<EnvVar> envVars = new ArrayList<>();

        //POD_NAME
        EnvVarSource podNameVarSource = new EnvVarSource();
        podNameVarSource.setFieldRef(new ObjectFieldSelector(null, "metadata.name"));
        envVars.add(new EnvVar("POD_NAME", null, podNameVarSource));

        //POD_NAMESPACE
        EnvVarSource podNamespaceVarSource = new EnvVarSource();
        podNamespaceVarSource.setFieldRef(new ObjectFieldSelector(null, "metadata.namespace"));
        envVars.add(new EnvVar("POD_NAMESPACE", null, podNamespaceVarSource));

        //POD_IP
        EnvVarSource podIpVarSource = new EnvVarSource();
        podIpVarSource.setFieldRef(new ObjectFieldSelector(null, "status.podIP"));
        envVars.add(new EnvVar("POD_IP", null, podIpVarSource));

        return envVars;
    }
}
