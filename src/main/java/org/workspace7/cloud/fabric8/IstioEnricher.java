package org.workspace7.cloud.fabric8;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.DeploymentSpec;
import io.fabric8.kubernetes.api.model.extensions.DeploymentSpecBuilder;
import io.fabric8.maven.core.config.ResourceConfig;
import io.fabric8.maven.core.handler.DeploymentHandler;
import io.fabric8.maven.core.handler.HandlerHub;
import io.fabric8.maven.core.util.Configs;
import io.fabric8.maven.core.util.MavenUtil;
import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.maven.enricher.api.BaseEnricher;
import io.fabric8.maven.enricher.api.EnricherContext;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * This enricher takes care of adding <a href="https://isito.io">Istio</a> related enrichments to the Kubernetes Deployment
 * <p>
 * TODO: once f8-m-p model has initContainers model , then annotations can be moved to templateSpec
 *
 * @author kameshs
 */
public class IstioEnricher extends BaseEnricher {

    private final DeploymentHandler deployHandler;

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
        initImage {{
            d = "docker.io/istio/init:0.1";
        }},
        coreDumpImage {{
            d = "alpine";
        }},
        proxyArgs {{
            d = "proxy,sidecar,-v,2";
        }},
        imagePullPolicy {{
            d = "Always";
        }};

        public String def() {
            return d;
        }

        protected String d;
    }

    public IstioEnricher(EnricherContext buildContext) {

        super(buildContext, "fmp-istio-enricher");
        HandlerHub handlerHub = new HandlerHub(buildContext.getProject());
        deployHandler = handlerHub.getDeploymentHandler();
    }

    //TODO - need to check if istio proxy side car is already there
    //TODO - adding init-containers to template spec
    //TODO - find out the other container  liveliness/readiness probes
    @Override
    public void addMissingResources(KubernetesListBuilder builder) {

        String[] proxyArgs = getConfig(Config.proxyArgs).split(",");
        List<String> sidecarArgs = new ArrayList<>();
        for (int i = 0; i < proxyArgs.length; i++) {
            sidecarArgs.add(proxyArgs[i]);
        }

        builder.accept(new TypedVisitor<DeploymentSpecBuilder>() {
            public void visit(DeploymentSpecBuilder deploymentSpecBuilder) {
                if ("yes".equalsIgnoreCase(getConfig(Config.enabled))) {
                    log.info("Adding Istio proxy");
                    String initContainerJson = buildInitContainers();
                    sidecarArgs.add("--passthrough");
                    sidecarArgs.add("8080");

                    deploymentSpecBuilder
                        .editOrNewTemplate()
                        .editOrNewMetadata()
                        .addToAnnotations("alpha.istio.io/sidecar", "injected")
                        .addToAnnotations("alpha.istio.io/version", "jenkins@ubuntu-16-04-build-12ac793f80be71-0.1.6-dab2033")
                        .addToAnnotations("pod.alpha.kubernetes.io/init-containers", initContainerJson)
                        .addToAnnotations("pod.beta.kubernetes.io/init-containers", initContainerJson)
                        .endMetadata()
                        .editOrNewSpec()
                        .addNewContainer()
                        .withName(getConfig(Config.proxyName))
                        .withResources(new ResourceRequirements())
                        .withTerminationMessagePath("/dev/termination-log")
                        .withImage(getConfig(Config.proxyImage))
                        .withImagePullPolicy(getConfig(Config.imagePullPolicy))
                        .withArgs(sidecarArgs)
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
     *
     * @return - list of {@link EnvVar}
     */
    protected List<EnvVar> proxyEnvVars() {
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

    /**
     * Builds the isito init containers that needs to be added to Istio Deployments via annotation
     * &quot;pod.beta.kubernetes.io/init-containers&quot;
     *
     * @return Json string that will set as value of the annotation
     */
    protected String buildInitContainers() {

        JsonArray initContainers = new JsonArray();

        //Init container 1
        JsonObject initContainer1 = new JsonObject()
            .put("name", "init")
            .put("image", getConfig(Config.initImage))
            .put("imagePullPolicy", "Always")
            .put("resources", new JsonObject())
            .put("terminationMessagePath", "/dev/termination-log")
            .put("terminationMessagePolicy", "File")
            .put("args", new JsonArray()
                .add("-p")
                .add("15001")
                .add("-u")
                .add("1337"))
            .put("securityContext",
                new JsonObject()
                    .put("capabilities",
                        new JsonObject()
                            .put("add", new JsonArray().add("NET_ADMIN"))));

        initContainers.add(initContainer1);

        //Init container 2
        JsonObject initContainer2 = new JsonObject()
            .put("name", "enable-core-dump")
            .put("image", getConfig(Config.coreDumpImage))
            .put("imagePullPolicy", "Always")
            .put("command", new JsonArray().add("/bin/sh"))
            .put("resources", new JsonObject())
            .put("terminationMessagePath", "/dev/termination-log")
            .put("terminationMessagePolicy", "File")
            .put("args", new JsonArray()
                .add("-c")
                .add("sysctl -w kernel.core_pattern=/tmp/core.%e.%p.%t \u0026\u0026 ulimit -c unlimited"))
            .put("securityContext",
                new JsonObject()
                    .put("privileged", true));

        initContainers.add(initContainer2);

        String json = initContainers.encode();
        log.debug("Adding Init Contianers {}", json);
        return json;
    }

}
