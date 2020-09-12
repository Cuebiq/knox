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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.ReaderInputStream;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.knox.gateway.dispatch.PassAllHeadersDispatch;
import org.apache.knox.gateway.filter.GatewayResponse;
import org.apache.knox.gateway.filter.GatewayResponseWrapper;
import org.apache.knox.gateway.filter.rewrite.impl.html.HtmlFilterReader;
import org.apache.knox.gateway.util.JsonPath;

import javax.activation.MimeType;
import javax.activation.MimeTypeParseException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.zip.GZIPOutputStream;

public class SupersetDispatcher extends PassAllHeadersDispatch {

    private static final int STREAM_BUFFER_SIZE = 8 * 1024;

    @Override
    protected void writeResponse(HttpServletRequest request, HttpServletResponse response, InputStream stream) throws IOException {
        if (response instanceof GatewayResponse) {
            if (((GatewayResponse) response).getMimeType().getSubType().equals("html")) {
                rewriteHtml(request, (GatewayResponseWrapper) response, stream);
            } else {
                ((GatewayResponse) response).streamResponse(stream);
            }
        } else {
            try (OutputStream output = response.getOutputStream()) {
                IOUtils.copy(stream, output);
            }
        }
    }

//    @Override
//    protected void executeRequest(HttpUriRequest outboundRequest, HttpServletRequest inboundRequest,
//                                  HttpServletResponse outboundResponse) throws IOException {
//        // partial workaround for KNOX-799
//        outboundRequest = fixTrailingSlash(outboundRequest, inboundRequest);
//        super.executeRequest(outboundRequest, inboundRequest, outboundResponse);
//    }
//
//    public static HttpUriRequest fixTrailingSlash(HttpUriRequest outboundRequest,
//                                                  HttpServletRequest inboundRequest) {
//        // preserve trailing slash from inbound request in the outbound request
//        if (inboundRequest.getPathInfo().endsWith("/")) {
//            String[] split = outboundRequest.getURI().toString().split("\\?");
//            if (!split[0].endsWith("/")) {
//                outboundRequest = RequestBuilder.copy(outboundRequest)
//                        .setUri(split[0] + "/" + (split.length == 2 ? "?" + split[1] : "/")).build();
//            }
//        }
//        return outboundRequest;
//    }

    private void rewriteHtml(HttpServletRequest request, GatewayResponseWrapper response, InputStream stream) throws IOException {
        try {
            String contextPath = request.getContextPath().concat("/analytics");
            final InputStream unFilteredStream;
            BufferedInputStream bufferedInputStream = new BufferedInputStream(stream, STREAM_BUFFER_SIZE);
            boolean isGzip = isGzip(bufferedInputStream);
            if (isGzip) {
                unFilteredStream = new GzipCompressorInputStream(bufferedInputStream, true);
            } else {
                unFilteredStream = bufferedInputStream;
            }
            ReaderInputStream modifiedInputStream = new ReaderInputStream(new SupersetHtmlFilterReader(new InputStreamReader(unFilteredStream, "UTF-8"), contextPath), "UTF-8");

            GatewayResponseWrapper gatewayResponseWrapper = response;

            OutputStream outputStream;
            if (isGzip) {
                outputStream = new GZIPOutputStream(gatewayResponseWrapper.getOutputStream(), STREAM_BUFFER_SIZE);
            } else {
                outputStream = gatewayResponseWrapper.getRawOutputStream();
            }
            gatewayResponseWrapper.streamResponse(modifiedInputStream, outputStream);

        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        }
    }


    private boolean isGzip(BufferedInputStream inBuffer) throws IOException {
        boolean isGzip = false;
        inBuffer.mark(2);
        byte[] signature = new byte[2];
        int len = inBuffer.read(signature);
        if (len == 2 && signature[0] == (byte) 0x1f && signature[1] == (byte) 0x8b) {
            isGzip = true;
        }
        inBuffer.reset();

        return isGzip;


    }

    private static class SupersetHtmlFilterReader extends HtmlFilterReader {

        private final String prefix;

        public SupersetHtmlFilterReader(Reader reader, String prefix) throws IOException, ParserConfigurationException {
            super(reader);
            this.prefix = prefix;

        }

        @Override
        protected String filterAttribute(String tagName, String attributeName, String attributeValue, String ruleName) {
            if (attributeName.equals("data-bootstrap")) {
                String data = StringEscapeUtils.unescapeHtml4(attributeValue);
                return StringEscapeUtils.escapeHtml4(rewrite(data));

            }
            return attributeValue;
        }

        @Override
        protected String filterText(String tagName, String text, String ruleName) {
            return text;
        }

        @Override
        public String filterValueString(String name, String value, String rule) {
            return value;
        }

        private String rewrite(String json) {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode jsonNode = objectMapper.readTree(new StringReader(json));
                rewriteNode(jsonNode, "$..url", "url");
                rewriteNode(jsonNode, "$..brand.icon", "icon");
                rewriteNode(jsonNode, "$..user_info_url", "user_info_url");
                rewriteNode(jsonNode, "$..user_login_url", "user_login_url");
                rewriteNode(jsonNode, "$..user_logout_url", "user_logout_url");
                rewriteNode(jsonNode, "$..brand.path", "path");
                return jsonNode.toString();
            } catch (IOException e) {
                e.printStackTrace();
            }
            ;
            return json;
        }

        private void rewriteNode(JsonNode jsonNode, String jsonPath, String key) {
            JsonPath.Expression compiled = JsonPath.compile(jsonPath);
            List<JsonPath.Match> evaluate = compiled.evaluate(jsonNode);
            evaluate.forEach(match -> {
                String path = match.getNode().asText();
//                String prefix = "/gateway/25/analytics";
                ((ObjectNode) match.getParent().getNode()).put(key, prefix.concat(path));
            });

        }
    }


//    public static void main(String[] args) {
//        String json = "{\n" +
//                "    \"user\": {\n" +
//                "        \"username\": \"rtotaro@cuebiq.com\",\n" +
//                "        \"firstName\": \"Rodolfo\",\n" +
//                "        \"lastName\": \"Totaro\",\n" +
//                "        \"userId\": 2,\n" +
//                "        \"isActive\": true,\n" +
//                "        \"createdOn\": \"2020-09-03T09:56:14.694320\",\n" +
//                "        \"email\": \"rtotaro@cuebiq.com\"\n" +
//                "    },\n" +
//                "    \"common\": {\n" +
//                "        \"flash_messages\": [],\n" +
//                "        \"conf\": {\n" +
//                "            \"SUPERSET_WEBSERVER_TIMEOUT\": 1000,\n" +
//                "            \"SUPERSET_DASHBOARD_POSITION_DATA_LIMIT\": 65535,\n" +
//                "            \"ENABLE_JAVASCRIPT_CONTROLS\": false,\n" +
//                "            \"DEFAULT_SQLLAB_LIMIT\": 1000,\n" +
//                "            \"SQL_MAX_ROW\": 100000,\n" +
//                "            \"SUPERSET_WEBSERVER_DOMAINS\": null,\n" +
//                "            \"SQLLAB_SAVE_WARNING_MESSAGE\": null,\n" +
//                "            \"DISPLAY_MAX_ROW\": 10000\n" +
//                "        },\n" +
//                "        \"locale\": \"en\",\n" +
//                "        \"language_pack\": {\n" +
//                "            \"domain\": \"superset\",\n" +
//                "            \"locale_data\": {\n" +
//                "                \"superset\": {\n" +
//                "                    \"\": {\n" +
//                "                        \"domain\": \"superset\",\n" +
//                "                        \"plural_forms\": \"nplurals=2; plural=(n != 1)\",\n" +
//                "                        \"lang\": \"en\"\n" +
//                "                    }\n" +
//                "                }\n" +
//                "            }\n" +
//                "        },\n" +
//                "        \"feature_flags\": {\n" +
//                "            \"CLIENT_CACHE\": false,\n" +
//                "            \"ENABLE_EXPLORE_JSON_CSRF_PROTECTION\": false,\n" +
//                "            \"KV_STORE\": false,\n" +
//                "            \"PRESTO_EXPAND_DATA\": false,\n" +
//                "            \"REDUCE_DASHBOARD_BOOTSTRAP_PAYLOAD\": false,\n" +
//                "            \"SHARE_QUERIES_VIA_KV_STORE\": false,\n" +
//                "            \"TAGGING_SYSTEM\": false,\n" +
//                "            \"SQLLAB_BACKEND_PERSISTENCE\": false\n" +
//                "        },\n" +
//                "        \"menu_data\": {\n" +
//                "            \"menu\": [\n" +
//                "                {\n" +
//                "                    \"name\": \"Security\",\n" +
//                "                    \"icon\": \"fa-cogs\",\n" +
//                "                    \"label\": \"Security\",\n" +
//                "                    \"childs\": [\n" +
//                "                        {\n" +
//                "                            \"name\": \"List Users\",\n" +
//                "                            \"icon\": \"fa-user\",\n" +
//                "                            \"label\": \"List Users\",\n" +
//                "                            \"url\": \"/users/list/\"\n" +
//                "                        },\n" +
//                "                        {\n" +
//                "                            \"name\": \"List Roles\",\n" +
//                "                            \"icon\": \"fa-group\",\n" +
//                "                            \"label\": \"List Roles\",\n" +
//                "                            \"url\": \"/roles/list/\"\n" +
//                "                        },\n" +
//                "                        {\n" +
//                "                            \"name\": \"User's Statistics\",\n" +
//                "                            \"icon\": \"fa-user-plus\",\n" +
//                "                            \"label\": \"User Registrations\",\n" +
//                "                            \"url\": \"/registeruser/list/\"\n" +
//                "                        },\n" +
//                "                        \"-\",\n" +
//                "                        {\n" +
//                "                            \"name\": \"Action Log\",\n" +
//                "                            \"icon\": \"fa-list-ol\",\n" +
//                "                            \"label\": \"Action Log\",\n" +
//                "                            \"url\": \"/logmodelview/list/\"\n" +
//                "                        }\n" +
//                "                    ]\n" +
//                "                },\n" +
//                "                {\n" +
//                "                    \"name\": \"Manage\",\n" +
//                "                    \"icon\": \"\",\n" +
//                "                    \"label\": \"Manage\",\n" +
//                "                    \"childs\": [\n" +
//                "                        {\n" +
//                "                            \"name\": \"Annotation Layers\",\n" +
//                "                            \"icon\": \"fa-comment\",\n" +
//                "                            \"label\": \"Annotation Layers\",\n" +
//                "                            \"url\": \"/annotationlayermodelview/list/\"\n" +
//                "                        },\n" +
//                "                        {\n" +
//                "                            \"name\": \"Annotations\",\n" +
//                "                            \"icon\": \"fa-comments\",\n" +
//                "                            \"label\": \"Annotations\",\n" +
//                "                            \"url\": \"/annotationmodelview/list/\"\n" +
//                "                        },\n" +
//                "                        {\n" +
//                "                            \"name\": \"CSS Templates\",\n" +
//                "                            \"icon\": \"fa-css3\",\n" +
//                "                            \"label\": \"CSS Templates\",\n" +
//                "                            \"url\": \"/csstemplatemodelview/list/\"\n" +
//                "                        },\n" +
//                "                        {\n" +
//                "                            \"name\": \"Queries\",\n" +
//                "                            \"icon\": \"fa-search\",\n" +
//                "                            \"label\": \"Queries\",\n" +
//                "                            \"url\": \"/queryview/list/\"\n" +
//                "                        },\n" +
//                "                        {\n" +
//                "                            \"name\": \"Import Dashboards\",\n" +
//                "                            \"icon\": \"fa-cloud-upload\",\n" +
//                "                            \"label\": \"Import Dashboards\",\n" +
//                "                            \"url\": \"/superset/import_dashboards\"\n" +
//                "                        }\n" +
//                "                    ]\n" +
//                "                },\n" +
//                "                {\n" +
//                "                    \"name\": \"Sources\",\n" +
//                "                    \"icon\": \"fa-database\",\n" +
//                "                    \"label\": \"Sources\",\n" +
//                "                    \"childs\": [\n" +
//                "                        {\n" +
//                "                            \"name\": \"Databases\",\n" +
//                "                            \"icon\": \"fa-database\",\n" +
//                "                            \"label\": \"Databases\",\n" +
//                "                            \"url\": \"/databaseview/list/\"\n" +
//                "                        },\n" +
//                "                        {\n" +
//                "                            \"name\": \"Tables\",\n" +
//                "                            \"icon\": \"fa-table\",\n" +
//                "                            \"label\": \"Tables\",\n" +
//                "                            \"url\": \"/tablemodelview/list/?_flt_1_is_sqllab_view=y\"\n" +
//                "                        },\n" +
//                "                        \"-\",\n" +
//                "                        {\n" +
//                "                            \"name\": \"Upload a CSV\",\n" +
//                "                            \"icon\": \"fa-upload\",\n" +
//                "                            \"label\": \"Upload a CSV\",\n" +
//                "                            \"url\": \"/csvtodatabaseview/form\"\n" +
//                "                        }\n" +
//                "                    ]\n" +
//                "                },\n" +
//                "                {\n" +
//                "                    \"name\": \"Charts\",\n" +
//                "                    \"icon\": \"fa-bar-chart\",\n" +
//                "                    \"label\": \"Charts\",\n" +
//                "                    \"url\": \"/chart/list/\"\n" +
//                "                },\n" +
//                "                {\n" +
//                "                    \"name\": \"Dashboards\",\n" +
//                "                    \"icon\": \"fa-dashboard\",\n" +
//                "                    \"label\": \"Dashboards\",\n" +
//                "                    \"url\": \"/dashboard/list/\"\n" +
//                "                },\n" +
//                "                {\n" +
//                "                    \"name\": \"SQL Lab\",\n" +
//                "                    \"icon\": \"fa-flask\",\n" +
//                "                    \"label\": \"SQL Lab\",\n" +
//                "                    \"childs\": [\n" +
//                "                        {\n" +
//                "                            \"name\": \"SQL Editor\",\n" +
//                "                            \"icon\": \"fa-flask\",\n" +
//                "                            \"label\": \"SQL Editor\",\n" +
//                "                            \"url\": \"/superset/sqllab\"\n" +
//                "                        },\n" +
//                "                        {\n" +
//                "                            \"name\": \"Saved Queries\",\n" +
//                "                            \"icon\": \"fa-save\",\n" +
//                "                            \"label\": \"Saved Queries\",\n" +
//                "                            \"url\": \"/sqllab/my_queries/\"\n" +
//                "                        },\n" +
//                "                        {\n" +
//                "                            \"name\": \"Query Search\",\n" +
//                "                            \"icon\": \"fa-search\",\n" +
//                "                            \"label\": \"Query Search\",\n" +
//                "                            \"url\": \"/superset/sqllab#search\"\n" +
//                "                        }\n" +
//                "                    ]\n" +
//                "                }\n" +
//                "            ],\n" +
//                "            \"brand\": {\n" +
//                "                \"path\": \"/superset/profile/rtotaro@cuebiq.com/\",\n" +
//                "                \"icon\": \"/static/assets/images/superset-logo@2x.png\",\n" +
//                "                \"alt\": \"Superset\"\n" +
//                "            },\n" +
//                "            \"navbar_right\": {\n" +
//                "                \"bug_report_url\": null,\n" +
//                "                \"documentation_url\": null,\n" +
//                "                \"version_string\": \"0.36.0\",\n" +
//                "                \"version_sha\": \"\",\n" +
//                "                \"languages\": {\n" +
//                "                    \"en\": {\n" +
//                "                        \"flag\": \"us\",\n" +
//                "                        \"name\": \"English\",\n" +
//                "                        \"url\": \"/lang/en\"\n" +
//                "                    },\n" +
//                "                    \"es\": {\n" +
//                "                        \"flag\": \"es\",\n" +
//                "                        \"name\": \"Spanish\",\n" +
//                "                        \"url\": \"/lang/es\"\n" +
//                "                    },\n" +
//                "                    \"it\": {\n" +
//                "                        \"flag\": \"it\",\n" +
//                "                        \"name\": \"Italian\",\n" +
//                "                        \"url\": \"/lang/it\"\n" +
//                "                    },\n" +
//                "                    \"fr\": {\n" +
//                "                        \"flag\": \"fr\",\n" +
//                "                        \"name\": \"French\",\n" +
//                "                        \"url\": \"/lang/fr\"\n" +
//                "                    },\n" +
//                "                    \"zh\": {\n" +
//                "                        \"flag\": \"cn\",\n" +
//                "                        \"name\": \"Chinese\",\n" +
//                "                        \"url\": \"/lang/zh\"\n" +
//                "                    },\n" +
//                "                    \"ja\": {\n" +
//                "                        \"flag\": \"jp\",\n" +
//                "                        \"name\": \"Japanese\",\n" +
//                "                        \"url\": \"/lang/ja\"\n" +
//                "                    },\n" +
//                "                    \"de\": {\n" +
//                "                        \"flag\": \"de\",\n" +
//                "                        \"name\": \"German\",\n" +
//                "                        \"url\": \"/lang/de\"\n" +
//                "                    },\n" +
//                "                    \"pt\": {\n" +
//                "                        \"flag\": \"pt\",\n" +
//                "                        \"name\": \"Portuguese\",\n" +
//                "                        \"url\": \"/lang/pt\"\n" +
//                "                    },\n" +
//                "                    \"pt_BR\": {\n" +
//                "                        \"flag\": \"br\",\n" +
//                "                        \"name\": \"Brazilian Portuguese\",\n" +
//                "                        \"url\": \"/lang/pt_BR\"\n" +
//                "                    },\n" +
//                "                    \"ru\": {\n" +
//                "                        \"flag\": \"ru\",\n" +
//                "                        \"name\": \"Russian\",\n" +
//                "                        \"url\": \"/lang/ru\"\n" +
//                "                    },\n" +
//                "                    \"ko\": {\n" +
//                "                        \"flag\": \"kr\",\n" +
//                "                        \"name\": \"Korean\",\n" +
//                "                        \"url\": \"/lang/ko\"\n" +
//                "                    }\n" +
//                "                },\n" +
//                "                \"show_language_picker\": true,\n" +
//                "                \"user_is_anonymous\": false,\n" +
//                "                \"user_info_url\": \"/users/userinfo/\",\n" +
//                "                \"user_logout_url\": \"/logout/\",\n" +
//                "                \"user_login_url\": \"/login/\",\n" +
//                "                \"locale\": \"en\"\n" +
//                "            }\n" +
//                "        }\n" +
//                "    }\n" +
//                "}";
//        System.out.println(new SupersetDispatcher().rewrite(json));
//    }
}
