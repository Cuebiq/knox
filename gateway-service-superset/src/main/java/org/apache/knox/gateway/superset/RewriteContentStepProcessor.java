package org.apache.knox.gateway.superset;

import org.apache.commons.io.IOUtils;
import org.apache.knox.gateway.GatewayServer;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriteEnvironment;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriteFilterContentDescriptor;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriteFilterPathDescriptor;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriteProcessor;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriteRuleDescriptor;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriteRulesDescriptor;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriteStepDescriptor;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriteStreamFilterFactory;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriter;
import org.apache.knox.gateway.filter.rewrite.impl.UrlRewriteContextImpl;
import org.apache.knox.gateway.filter.rewrite.impl.UrlRewriteFilterContentDescriptorImpl;
import org.apache.knox.gateway.filter.rewrite.impl.UrlRewriteRulesDescriptorImpl;
import org.apache.knox.gateway.filter.rewrite.impl.UrlRewriteUtil;
import org.apache.knox.gateway.filter.rewrite.impl.javascript.JavaScriptUrlRewriteFilterReader;
import org.apache.knox.gateway.filter.rewrite.spi.UrlRewriteContext;
import org.apache.knox.gateway.filter.rewrite.spi.UrlRewriteStepDescriptorBase;
import org.apache.knox.gateway.filter.rewrite.spi.UrlRewriteStepProcessor;
import org.apache.knox.gateway.filter.rewrite.spi.UrlRewriteStepStatus;
import org.apache.knox.gateway.filter.rewrite.spi.UrlRewriteStreamFilter;
import org.apache.knox.gateway.service.definition.ServiceDefinitionPair;
import org.apache.knox.gateway.services.GatewayServices;
import org.apache.knox.gateway.services.ServiceType;
import org.apache.knox.gateway.services.registry.ServiceDefEntry;
import org.apache.knox.gateway.services.registry.ServiceDefinitionRegistry;
import org.apache.knox.gateway.services.registry.ServiceRegistry;
import org.apache.knox.gateway.util.MimeTypes;
import org.apache.knox.gateway.util.TopologyUtils;
import org.apache.knox.gateway.util.urltemplate.Params;
import org.apache.knox.gateway.util.urltemplate.Parser;
import org.apache.knox.gateway.util.urltemplate.Resolver;
import org.apache.knox.gateway.util.urltemplate.Template;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class RewriteContentStepProcessor implements UrlRewriteStepProcessor<RewriteContentStepDescriptor> {

    private RewriteContentStepDescriptor descriptor;
    private UrlRewriteEnvironment environment;
    private UrlRewriteProcessor urlRewriteProcessor;

    @Override
    public String getType() {
        return "rewrite-content";
    }

    @Override
    public void initialize(UrlRewriteEnvironment environment, RewriteContentStepDescriptor descriptor) throws Exception {

        this.descriptor = descriptor;
        this.environment = environment;
        urlRewriteProcessor = new UrlRewriteProcessor();



        ServiceDefinitionRegistry serviceRegistry = GatewayServer.getGatewayServices().getService(ServiceType.SERVICE_DEFINITION_REGISTRY);
        Set<ServiceDefinitionPair> serviceDefinitions = serviceRegistry.getServiceDefinitions();

        Optional<ServiceDefinitionPair> superset = serviceDefinitions.stream().filter(serviceDefinitionPair -> serviceDefinitionPair.getService().getRole().equals("SUPERSET")).findFirst();
        if(superset.isPresent())
        {
            UrlRewriteRulesDescriptor rewriteRules = superset.get().getRewriteRules();

            
            UrlRewriteRulesDescriptorImpl contextUrlRewriteRulesDescriptor = new UrlRewriteRulesDescriptorImpl();

            List<UrlRewriteRuleDescriptor> filteredRules = rewriteRules.getRules().stream()
                    .filter(urlRewriteRuleDescriptor ->
                            !urlRewriteRuleDescriptor.steps()
                                    .stream()
                                    .filter(urlRewriteStepDescriptor -> urlRewriteStepDescriptor.type().equals("rewrite-content"))
                                    .findFirst().isPresent()).collect(Collectors.toList());

            filteredRules.forEach(urlRewriteRuleDescriptor -> contextUrlRewriteRulesDescriptor.addRule(urlRewriteRuleDescriptor));
            rewriteRules.getFilters().forEach(urlRewriteFilterDescriptor -> contextUrlRewriteRulesDescriptor.addFilter(urlRewriteFilterDescriptor));
            rewriteRules.getFunctions().forEach(urlRewriteFunctionDescriptor -> contextUrlRewriteRulesDescriptor.addFunction(urlRewriteFunctionDescriptor.name()));


            urlRewriteProcessor.initialize(environment,contextUrlRewriteRulesDescriptor);

        }
    }

    @Override
    public UrlRewriteStepStatus process(UrlRewriteContext context) throws Exception {
        Template originalUrl = context.getOriginalUrl();

        UrlRewriteStreamFilter filter = UrlRewriteStreamFilterFactory.create(MimeTypes.create(descriptor.getContentType(), "UTF-8"), null);


        UrlRewriteFilterContentDescriptorImpl urlRewriteFilterContentDescriptor = new UrlRewriteFilterContentDescriptorImpl();
        urlRewriteFilterContentDescriptor.type(descriptor.getContentType());
        urlRewriteFilterContentDescriptor.addApply(descriptor.getPattern(),descriptor.getRule());

        InputStream rewrittenContent = filter.filter(new ByteArrayInputStream(originalUrl.toString().getBytes("UTF-8")),
                "UTF-8",
                urlRewriteProcessor, environment, UrlRewriter.Direction.OUT, urlRewriteFilterContentDescriptor);

        context.setCurrentUrl(Parser.parseLiteral(IOUtils.toString(rewrittenContent,"UTF-8")));



        return UrlRewriteStepStatus.SUCCESS;
    }

    @Override
    public void destroy() throws Exception {

    }
}
