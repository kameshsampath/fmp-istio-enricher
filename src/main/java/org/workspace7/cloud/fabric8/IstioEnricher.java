package org.workspace7.cloud.fabric8;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.DeploymentSpecBuilder;
import io.fabric8.maven.core.util.Configs;
import io.fabric8.maven.enricher.api.BaseEnricher;
import io.fabric8.maven.enricher.api.EnricherContext;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * This enricher takes care of adding <a href="https://isito.io">Istio</a> related enrichments to the Kubernetes Deployment
 *
 * TODO: once f8-m-p model has initContainers model , then annotations can be moved to templateSpec
 * @author kameshs
 */
public class IstioEnricher extends BaseEnricher {

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
        proxyArgs {{
            d = "proxy,sidecar,-v,2";
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
                        .addToAnnotations("pod.beta.kubernetes.io/init-containers", buildInitContainers())
                        .endMetadata()
                        .editOrNewSpec()
                        .addNewContainer()
                        .withName(getConfig(Config.proxyName))
                        .withImage(getConfig(Config.proxyImage))
                        .withImagePullPolicy(getConfig(Config.imagePullPolicy))
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
     * @return Json string that will set as value of the annotation
     */
    protected String buildInitContainers() {
        JSONArray initContainers = new JSONArray();

        //init container
        //"'[{\"args\":[\"-p\",\"15001\",\"-u\",\"1337\"],\"image\":\"docker.io/istio/init:0.1\",\"imagePullPolicy\":\"IfNotPresent\"," +
        //    ""name":"init","securityContext":{"capabilities":{"add":["NET_ADMIN"]}}}"
        JSONObject container1 = new JSONObject();

        JSONArray c1ArgsArray = new JSONArray();
        c1ArgsArray.put("-p");
        c1ArgsArray.put("15001");
        c1ArgsArray.put("-u");
        c1ArgsArray.put("1337");
        container1.put("args", c1ArgsArray);

        container1.put("image", getConfig(Config.initImage));
        container1.put("imagePullPolicy", getConfig(Config.imagePullPolicy));
        container1.put("name", "init");


        JSONArray c1ScCapsArray = new JSONArray();
        c1ScCapsArray.put("NET_ADMIN");
        JSONObject c1ScCapsAdd = new JSONObject();
        c1ScCapsAdd.put("add", c1ScCapsArray);
        JSONObject scContext = new JSONObject();
        scContext.put("capabilities", c1ScCapsAdd);
        container1.put("securityContext", scContext);
        initContainers.put(container1);

        //enable-core-dump container
        // "args":["-c","sysctl"," -w kernel.core_pattern=/tmp/core.%e.%p.%t \\u0026\\u0026 ulimit -c unlimited\"],\"command\":[\"/bin/sh\"]," +
        // "\"image\":\"alpine\",\"imagePullPolicy\":\"IfNotPresent\",\"name\":\"enable-core-dump\",\"securityContext\":{\"privileged\":true}}]'"

        JSONObject container2 = new JSONObject();

        JSONArray c2ArgsArray = new JSONArray();
        c2ArgsArray.put("-c");
        c2ArgsArray.put("sysctl");
        c2ArgsArray.put("-w kernel.core_pattern=/tmp/core.%e.%p.%t \\u0026\\u0026 ulimit -c unlimited\\");
        c2ArgsArray.put("-1337");
        container2.put("args", c2ArgsArray);

        container2.put("image", "alpine");
        container2.put("imagePullPolicy", getConfig(Config.imagePullPolicy));
        container2.put("name", "enable-core-dump");


        JSONArray cmdArray = new JSONArray();
        cmdArray.put("/bin/sh");
        container2.put("command", cmdArray);

        JSONObject scContext2 = new JSONObject();
        scContext2.put("privileged", true);
        container2.put("securityContext", scContext2);

        initContainers.put(container2);

        StringWriter jsonWriter = new StringWriter();
        initContainers.write(jsonWriter);
        jsonWriter.flush();
        String json = jsonWriter.toString();
        log.debug("INIT CONTAINERS:{}", json);
        return json;
    }
}
