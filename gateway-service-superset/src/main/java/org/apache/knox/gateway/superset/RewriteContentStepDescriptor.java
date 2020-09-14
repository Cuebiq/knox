package org.apache.knox.gateway.superset;

import org.apache.knox.gateway.filter.rewrite.api.UrlRewriteStepDescriptor;
import org.apache.knox.gateway.filter.rewrite.spi.UrlRewriteStepDescriptorBase;

public class RewriteContentStepDescriptor extends UrlRewriteStepDescriptorBase<RewriteContentStepDescriptor>
        implements UrlRewriteStepDescriptor<RewriteContentStepDescriptor> {

    private String contentType;

    private String pattern;

    private String rule;

    public RewriteContentStepDescriptor() {
        super("rewrite-content");
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getRule() {
        return rule;
    }

    public void setRule(String rule) {
        this.rule = rule;
    }
}
