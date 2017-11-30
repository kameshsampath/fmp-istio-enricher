package org.workspace7.fmp;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.extensions.DeploymentSpec;
import io.fabric8.kubernetes.api.model.extensions.DeploymentSpecBuilder;
import io.fabric8.maven.core.config.ResourceConfig;
import io.fabric8.maven.core.handler.DeploymentHandler;
import io.fabric8.maven.core.handler.HandlerHub;
import io.fabric8.maven.core.util.Configs;
import io.fabric8.maven.core.util.KubernetesResourceUtil;
import io.fabric8.maven.core.util.MavenUtil;
import io.fabric8.maven.docker.config.ImageConfiguration;
import io.fabric8.maven.enricher.api.BaseEnricher;
import io.fabric8.maven.enricher.api.EnricherContext;
import io.fabric8.openshift.api.model.DeploymentConfigSpecBuilder;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.*;

/**
 * This enricher takes care of adding <a href="https://isito.io">Istio</a> related enrichments to the Kubernetes Deployment
 *
 * @author kameshs
 */
public class IstioKubeInjectEnricher extends BaseEnricher {

    private static final String ISTIO_ANNOTATION_STATUS = "injected-version-releng@0d29a2c0d15f-0.2.12-998e0e00d375688bcb2af042fc81a60ce5264009";
    private final DeploymentHandler deployHandler;

    // Available configuration keys
    private enum Config implements Configs.Key {
        name,
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
            d = "proxy,sidecar,-v,2,--configPath,/etc/istio/proxy,--binaryPath,/usr/local/bin/envoy,--serviceCluster,app-cluster-name," +
                "--drainDuration,45s,--parentShutdownDuration,1m0s,--discoveryAddress,istio-pilot.istio-system:8080,--discoveryRefreshDelay," +
                "1s,--zipkinAddress,zipkin.istio-system:9411,--connectTimeout,10s,--statsdUdpAddress,istio-mixer.istio-system:9125,--proxyAdminPort,15000";
        }},
        imagePullPolicy {{
            d = "IfNotPresent";
        }},
        replicaCount {{
            d = "1";
        }};

        public String def() {
            return d;
        }

        protected String d;
    }

    public IstioKubeInjectEnricher(EnricherContext buildContext) {
        super(buildContext, "fmp-istio-enricher");
        HandlerHub handlerHub = new HandlerHub(buildContext.getProject());
        deployHandler = handlerHub.getDeploymentHandler();

    }

    //TODO - need to check if istio proxy side car is already there
    //TODO - adding init-containers to template spec
    //TODO - find out the other container  liveliness/readiness probes
    @Override
    public void addMissingResources(KubernetesListBuilder builder) {
        if ("yes".equalsIgnoreCase(getConfig(Config.enabled))) {
            if ("yes".equalsIgnoreCase(getConfig(Config.enabled))) {
                getContext().getImages().add(istioProxyImageConfig());
            }

            final String name = getConfig(Config.name, MavenUtil.createDefaultResourceName(getProject()));

            final ResourceConfig config = new ResourceConfig.Builder()
                .controllerName(name)
                .imagePullPolicy(getConfig(Config.imagePullPolicy))
                .withReplicas(Configs.asInt(getConfig(Config.replicaCount)))
                .build();

            final List<ImageConfiguration> images = getImages();

            String[] proxyArgs = getConfig(Config.proxyArgs).split(",");
            List<String> sidecarArgs = new ArrayList<>();
            for (int i = 0; i < proxyArgs.length; i++) {
                //cluster name defaults to app name a.k.a controller name
                if("app-cluster-name".equalsIgnoreCase(proxyArgs[i])){
                    sidecarArgs.add(name);
                }else {
                    sidecarArgs.add(proxyArgs[i]);
                }
            }

            final DeploymentSpec spec = deployHandler.getDeployment(config, images).getSpec();

            if (spec != null) {
                builder.accept(new TypedVisitor<DeploymentBuilder>() {
                    @Override
                    public void visit(DeploymentBuilder deploymentBuilder) {
                        log.info("Adding Istio proxy");
                        deploymentBuilder.editOrNewSpec().editOrNewTemplate()
                            // MetaData
                            .editOrNewMetadata()
                            .addToAnnotations("sidecar.istio.io/status", ISTIO_ANNOTATION_STATUS)
                            .endMetadata()
                            .editOrNewSpec()
                            .endSpec()
                            .endTemplate()
                            .endSpec();
                        //TODO - need to analyze a bit before removing it
                        //mergeDeploymentSpec(deploymentBuilder, spec);
                    }
                });

                if (spec.getTemplate() != null && spec.getTemplate().getSpec() != null) {
                    //final PodSpec podSpec = spec.getTemplate().getSpec();
                    builder.accept(new TypedVisitor<PodSpecBuilder>() {
                        @Override
                        public void visit(PodSpecBuilder podSpecBuilder) {
                            sidecarArgs.add("--passthrough");
                            //FIXME: get this port dynamically from the context
                            sidecarArgs.add("8080");
                            podSpecBuilder
                                // Add Istio Proxy, Volumes and Secret
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
                                    .withPrivileged(true)
                                    .withReadOnlyRootFilesystem(false)
                                    .build())
                                .withVolumeMounts(istioVolumeMounts())
                                .endContainer()
                                .withVolumes(istioVolumes())
                                // Add Istio Init container and Core Dump
                                .withInitContainers(istioInitContainer(), coreDumpInitContainer());
                            //TODO - need to analyze a bit before removing it
                            //KubernetesResourceUtil.mergePodSpec(builder, podSpec, name);
                        }
                    });
                }
            }
        }
    }

    protected Container istioInitContainer() {
        /*
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
         */

        return new ContainerBuilder()
            .withName("istio-init")
            .withImage(getConfig(Config.initImage))
            .withImagePullPolicy("IfNotPresent")
            .withTerminationMessagePath("/dev/termination-log")
            .withTerminationMessagePolicy("File")
            .withArgs("-p", "15001", "-u", "1337")
            .withSecurityContext(new SecurityContextBuilder()
                .withPrivileged(true)
                .withCapabilities(new CapabilitiesBuilder()
                    .addToAdd("NET_ADMIN")
                    .build())
                .build())
            .build();
    }

    protected Container coreDumpInitContainer() {
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
        return new ContainerBuilder()
            .withName("enable-core-dump")
            .withImage(getConfig(Config.coreDumpImage))
            .withImagePullPolicy("IfNotPresent")
            .withCommand("/bin/sh")
            .withArgs("-c", " sysctl -w kernel.core_pattern=/etc/istio/proxy/core.%e.%p.%t && ulimit -c unlimited")
            .withTerminationMessagePath("/dev/termination-log")
            .withTerminationMessagePolicy("File")
            .withSecurityContext(new SecurityContextBuilder()
                .withPrivileged(true)
                .build())
            .build();
    }


    /**
     * Generate the volumes to be mounted
     *
     * @return - list of {@link VolumeMount}
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
     * Generate the volumes
     *
     * @return - list of {@link Volume}
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


    private ImageConfiguration istioProxyImageConfig() {
        ImageConfiguration.Builder builder = new ImageConfiguration.Builder();
        builder.name(getConfig(Config.proxyName))
            .registry("docker.io/istio");
        return builder.build();
    }

}
