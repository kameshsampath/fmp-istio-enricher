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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            d = "istio-proxy";
        }},
        proxyImage {{
            d = "docker.io/istio/proxy_debug:0.2.12";
        }},
        initImage {{
            d = "docker.io/istio/proxy_init:0.2.12";
        }},
        coreDumpImage {{
            d = "alpine";
        }},
        proxyArgs {{
            d = "proxy,sidecar,-v,2,--configPath,/etc/istio/proxy,--binaryPath,/usr/local/bin/envoy,--serviceCluster,helloworld," +
                "--drainDuration,45s,--parentShutdownDuration,1m0s,--discoveryAddress,istio-pilot.istio-system:8080,--discoveryRefreshDelay," +
                "1s,--zipkinAddress,zipkin.istio-system:9411,--connectTimeout,10s,--statsdUdpAddress,istio-mixer.istio-system:9125,--proxyAdminPort,\"15000\"";
        }},
        imagePullPolicy {{
            d = "IfNotPresent";
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

                    /* Proxy Def
                    * args:
                    *     - proxy
                    *     - sidecar
                    *     - -v
                    *     - "2"
                    *     - --configPath
                    *     - /etc/istio/proxy
                    *     - --binaryPath
                    *     - /usr/local/bin/envoy
                    *     - --serviceCluster
                    *     - helloworld
                    *     - --drainDuration
                    *     - 45s
                    *     - --parentShutdownDuration
                    *     - 1m0s
                    *     - --discoveryAddress
                    *     - istio-pilot.istio-system:8080
                    *     - --discoveryRefreshDelay
                    *     - 1s
                    *     - --zipkinAddress
                    *     - zipkin.istio-system:9411
                    *     - --connectTimeout
                    *     - 10s
                    *     - --statsdUdpAddress
                    *     - istio-mixer.istio-system:9125
                    *     - --proxyAdminPort
                    *     - "15000"
                    * env:
                    * - name: POD_NAME
                    * valueFrom:
                    * fieldRef:
                    * fieldPath: metadata.name
                    *     - name: POD_NAMESPACE
                    * valueFrom:
                    * fieldRef:
                    * fieldPath: metadata.namespace
                    *     - name: INSTANCE_IP
                    * valueFrom:
                    * fieldRef:
                    * fieldPath: status.podIP
                    * image: docker.io/istio/proxy_debug:0.2.12
                    * imagePullPolicy: IfNotPresent
                    * name: istio-proxy
                    * resources: {}
                    * securityContext:
                    *   privileged: true
                    *   readOnlyRootFilesystem: false
                    *   runAsUser: 1337
                    * volumeMounts:
                    * - mountPath: /etc/istio/proxy
                    *   name: istio-envoy
                    * - mountPath: /etc/certs/
                    *   name: istio-certs
                    *   readOnly: true
                    */

                    deploymentSpecBuilder
                        .editOrNewTemplate()
                        .editOrNewMetadata()
                        //.addToAnnotations("alpha.istio.io/sidecar", "injected")
                        //.addToAnnotations("alpha.istio.io/version", "jenkins@ubuntu-16-04-build-12ac793f80be71-0.1.6-dab2033")
                        .addToAnnotations("sidecar.istio.io/status", "injected-version-releng@0d29a2c0d15f-0.2.12-998e0e00d375688bcb2af042fc81a60ce5264009")
                        //.addToAnnotations("pod.alpha.kubernetes.io/init-containers", initContainerJson)
                        //.addToAnnotations("pod.beta.kubernetes.io/init-containers", initContainerJson)
                        .addToAnnotations("sidecar.istio.io/status",initContainerJson)
                        .endMetadata()
                        .editOrNewSpec()
                        .addNewContainer()
                        // See Proxy def
                        .withName(getConfig(Config.proxyName))
                        .withResources(new ResourceRequirements())
                        .withTerminationMessagePath("/dev/termination-log")
                        .withImage(getConfig(Config.proxyImage))
                        .withImagePullPolicy(getConfig(Config.imagePullPolicy))
                        .withArgs(sidecarArgs)
                        .withEnv(proxyEnvVars())
                        .withSecurityContext(new SecurityContextBuilder()
                            .withRunAsUser(1337l)
                            .withPrivileged(true)
                            .withReadOnlyRootFilesystem(false)
                            .build())
                        .withVolumeMounts(istioVolumeMounts())
                        .endContainer()
                        .withVolumes(istioVolumes())
                        .endSpec()
                        .endTemplate();
                }
            }
        });
    }

    /**
     *
     *
     */
    protected Map<String, String> istioSecretMap() {
        Map<String, String> map = new HashMap<>();
        map.put("defaultMode","420");
        map.put("optional","true");
        return map;
    }

    /**
     *
     *
     */
    protected List<VolumeMount> istioVolumeMounts() {
        List<VolumeMount> volumeMounts = new ArrayList<>();

        VolumeMountBuilder istioProxyVolume = new VolumeMountBuilder();
        istioProxyVolume
            .withMountPath("/etc/istio/proxy")
            .withName("istio-envoy")
        .build();

        VolumeMountBuilder istioCertsVolume = new VolumeMountBuilder();
        istioCertsVolume
            .withMountPath("/etc/certs")
            .withName("istio-certs")
            .withReadOnly(true)
        .build();

        volumeMounts.add(istioProxyVolume.build());
        volumeMounts.add(istioCertsVolume.build());
        return volumeMounts;
    }

    /**
     *
     *
     */
    protected List<Volume> istioVolumes() {
        List<Volume> volumes = new ArrayList<>();

        VolumeBuilder empTyVolume = new VolumeBuilder();
        empTyVolume.withEmptyDir(new EmptyDirVolumeSourceBuilder()
            .withMedium("Memory")
            .build())
            .withName("istio-envoy")
        .build();

        VolumeBuilder secretVolume = new VolumeBuilder();
        secretVolume
            .withName("istio-certs")
              .withSecret(new SecretVolumeSourceBuilder()
              .withSecretName("istio.default")
              .withDefaultMode(420)
              .build())
            .build();

        volumes.add(empTyVolume.build());
        volumes.add(secretVolume.build());
        return volumes;
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

        /* Istio Proxy Init
         *   args:
         *     - '-p'
         *     - '15001'
         *     - '-u'
         *     - '1337'
         *   image: 'docker.io/istio/proxy_init:0.2.12'
         *   imagePullPolicy: IfNotPresent
         *   name: istio-init
         *   resources: {}
         *   securityContext:
         *     capabilities:
         *       add:
         *         - NET_ADMIN
         *     privileged: true
         *   terminationMessagePath: /dev/termination-log
         *   terminationMessagePolicy: File
         */
        JsonObject initContainer1 = new JsonObject()
            .put("name", "istio-init")
            .put("image", getConfig(Config.initImage))
            .put("imagePullPolicy", "IfNotPresent")
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
                            .put("add", new JsonArray().add("NET_ADMIN")))
                    .put("privileged",true));

        initContainers.add(initContainer1);

        /* Enable Core Dump
         *  args:
         *   - '-c'
         *   - >-
         *     sysctl -w kernel.core_pattern=/etc/istio/proxy/core.%e.%p.%t &&
         *     ulimit -c unlimited
         * command:
         *   - /bin/sh
         * image: alpine
         * imagePullPolicy: IfNotPresent
         * name: enable-core-dump
         * resources: {}
         * securityContext:
         *   privileged: true
         * terminationMessagePath: /dev/termination-log
         * terminationMessagePolicy: File
         */
        JsonObject initContainer2 = new JsonObject()
            .put("name", "enable-core-dump")
            .put("image", getConfig(Config.coreDumpImage))
            .put("imagePullPolicy", "IfNotPresent")
            .put("command", new JsonArray().add("/bin/sh"))
            .put("resources", new JsonObject())
            .put("terminationMessagePath", "/dev/termination-log")
            .put("terminationMessagePolicy", "File")
            .put("args", new JsonArray()
                .add("-c")
                .add("sysctl -w kernel.core_pattern=/etc/istio/proxy/core.%e.%p.%t \u0026\u0026 ulimit -c unlimited"))
            .put("securityContext",
                new JsonObject()
                    .put("privileged", true))
            .put("terminationMessagePath", "/dev/termination-log")
            .put("terminationMessagePolicy", "File");

        initContainers.add(initContainer2);

        String json = initContainers.encode();
        log.debug("Adding Init Containers {}", json);
        return json;
    }

}
