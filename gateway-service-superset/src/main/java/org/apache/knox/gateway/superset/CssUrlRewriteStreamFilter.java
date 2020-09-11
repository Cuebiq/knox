package org.apache.knox.gateway.superset;/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.helger.commons.io.stream.StringInputStream;
import com.helger.css.ECSSVersion;
import com.helger.css.decl.CSSDeclaration;
import com.helger.css.decl.CSSDeclarationList;
import com.helger.css.decl.CascadingStyleSheet;
import com.helger.css.decl.visit.AbstractModifyingCSSUrlVisitor;
import com.helger.css.decl.visit.CSSVisitor;
import com.helger.css.decl.visit.CSSVisitorForUrl;
import com.helger.css.reader.CSSReader;
import com.helger.css.reader.CSSReaderDeclarationList;
import com.helger.css.writer.CSSWriter;
import org.apache.commons.io.IOUtils;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriteFilterApplyDescriptor;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriteFilterContentDescriptor;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriteFilterPathDescriptor;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriter;
import org.apache.knox.gateway.filter.rewrite.i18n.UrlRewriteMessages;
import org.apache.knox.gateway.filter.rewrite.impl.UrlRewriteFilterReader;
import org.apache.knox.gateway.filter.rewrite.spi.UrlRewriteStreamFilter;
import org.apache.knox.gateway.i18n.messages.MessagesFactory;
import org.apache.knox.gateway.util.urltemplate.Parser;
import org.apache.knox.gateway.util.urltemplate.Resolver;
import org.apache.knox.gateway.util.urltemplate.Template;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CssUrlRewriteStreamFilter implements UrlRewriteStreamFilter {

    private static final UrlRewriteMessages LOG = MessagesFactory.get(UrlRewriteMessages.class);
    private static final UrlRewriteFilterPathDescriptor.Compiler<Pattern> REGEX_COMPILER = new UrlRewriteFilterReader.RegexCompiler();

    private static String[] TYPES = new String[]{"text/css"};
    private static String[] NAMES = new String[]{null};

    @Override
    public String[] getTypes() {
        return TYPES;
    }

    @Override
    public String[] getNames() {
        return NAMES;
    }

    @Override
    public InputStream filter(
            InputStream stream,
            String encoding,
            final UrlRewriter rewriter,
            final Resolver resolver,
            final UrlRewriter.Direction direction,
            final UrlRewriteFilterContentDescriptor config)
            throws IOException {


        String sCSSString = IOUtils.toString(stream, "UTF-8");

        final CascadingStyleSheet aCSS = CSSReader.readFromString(sCSSString,
                ECSSVersion.CSS30);


        if(aCSS==null)
        {
            return new StringInputStream(sCSSString, Charset.forName("UTF-8"));
        }
        final AbstractModifyingCSSUrlVisitor modifyingCSSUrlVisitor = new AbstractModifyingCSSUrlVisitor() {
            @Nonnull
            @Override
            protected String getModifiedURI(@Nonnull String s) {
                return rewrite(s,rewriter,resolver,direction,config);
            }
        };
        CSSVisitor.visitCSS(aCSS,new CSSVisitorForUrl(modifyingCSSUrlVisitor));


        String cssAsString = new CSSWriter(ECSSVersion.CSS30, true).getCSSAsString(aCSS);

        return new StringInputStream(cssAsString, Charset.forName("UTF-8"));

    }


    private String rewrite(String value, UrlRewriter rewriter,
                           Resolver resolver,
                           UrlRewriter.Direction direction, UrlRewriteFilterContentDescriptor config) {
        try {
            Template input = Parser.parseLiteral(value);
            Template output = rewriter.rewrite(resolver, input, direction, getRuleName(value, config));
            if (output != null) {
                String rewrittenValue = output.getPattern();
                return rewrittenValue;
            }
        } catch (URISyntaxException e) {
            LOG.failedToParseValueForUrlRewrite(value);
        }
        return value;
    }

    private String getRuleName(String inputValue, UrlRewriteFilterContentDescriptor config) {
        if (config != null && !config.getSelectors().isEmpty()) {
            for (UrlRewriteFilterPathDescriptor<?> selector : config.getSelectors()) {
                if (selector instanceof UrlRewriteFilterApplyDescriptor) {
                    UrlRewriteFilterApplyDescriptor apply = (UrlRewriteFilterApplyDescriptor) selector;
                    Matcher matcher = apply.compiledPath(REGEX_COMPILER).matcher(inputValue);
                    if (matcher.matches()) {
                        return apply.rule();
                    }
                }
            }
        }
        return null;
    }

}
