/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.hindesight.nifi.processors.jbrowserdriver;

import com.google.gson.Gson;
import com.machinepublishers.jbrowserdriver.JBrowserDriver;
import com.machinepublishers.jbrowserdriver.ProxyConfig;
import com.machinepublishers.jbrowserdriver.Settings;
import com.machinepublishers.jbrowserdriver.Timezone;
import org.apache.nifi.annotation.behavior.*;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.util.StandardValidators;
import org.openqa.selenium.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

import static org.apache.commons.lang.StringUtils.trimToEmpty;

@Tags({"jbrowserdriver","http","https","javascript","browser"})
@CapabilityDescription("Invokes a JBrowserDriver HTTP request")
@InputRequirement(InputRequirement.Requirement.INPUT_ALLOWED)
@WritesAttributes({
        @WritesAttribute(attribute = "invokebrowserhttp.status.code", description = "The status code that is returned"),
        @WritesAttribute(attribute = "invokebrowser.http.status.message", description = "The status messaage that is returned")
})
public class InvokeBrowserHTTP extends AbstractProcessor {
    public static final String STATUS_CODE = "invokebrowser.http.status.code";
    public static final String STATUS_MESSAGE = "invokebrowser.http.status.message";

    public static final Set<String> IGNORED_ATTRIBUTES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(STATUS_CODE, STATUS_MESSAGE,"uuid", "filename", "path"))
    );

    public static final PropertyDescriptor PROP_URL = new PropertyDescriptor.Builder()
            .name("Remote URL")
            .description("Example Property")
            .required(true)
            .expressionLanguageSupported(true)
            .addValidator(StandardValidators.URL_VALIDATOR)
            .build();

    public static final PropertyDescriptor PROP_ACTIONS = new PropertyDescriptor.Builder()
            .name("Page Actions")
            .description("Actions")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor PROP_SCREENSHOT = new PropertyDescriptor.Builder()
            .name("Grab Screenshot")
            .description("Blah blah")
            .required(false)
            .defaultValue("False")
            .allowableValues("True", "False")
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .build();

    public static final PropertyDescriptor PROP_CONNECT_TIMEOUT = new PropertyDescriptor.Builder()
            .name("Connection Timeout")
            .description("Connection timeout.")
            .required(true)
            .defaultValue("5 secs")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    public static final PropertyDescriptor PROP_SOCKET_TIMEOUT = new PropertyDescriptor.Builder()
            .name("Socket Timeout")
            .description("Socket timeout.")
            .required(true)
            .defaultValue("5 secs")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    public static final PropertyDescriptor PROP_PROXY_HOST = new PropertyDescriptor.Builder()
            .name("Proxy Host")
            .description("The fully qualified hostname or IP address of the proxy server")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor PROP_PROXY_PORT = new PropertyDescriptor.Builder()
            .name("Proxy Port")
            .description("The port of the proxy server")
            .required(false)
            .addValidator(StandardValidators.PORT_VALIDATOR)
            .build();

    public static final Relationship REL_RESPONSE = new Relationship.Builder()
            .name("Response")
            .description("Example relationship")
            .build();

    public static final Relationship REL_SCREENSHOT = new Relationship.Builder()
            .name("Screenshot")
            .description("Screenshot output")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("Failure")
            .description("Request failed")
            .build();

    private List<PropertyDescriptor> descriptors;

    private Set<Relationship> relationships;

    private JBrowserDriver driver;

    private Gson gson;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
        descriptors.add(PROP_URL);
        descriptors.add(PROP_SCREENSHOT);
        descriptors.add(PROP_ACTIONS);
        descriptors.add(PROP_CONNECT_TIMEOUT);
        descriptors.add(PROP_SOCKET_TIMEOUT);
        descriptors.add(PROP_PROXY_HOST);
        descriptors.add(PROP_PROXY_PORT);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<Relationship>();
        relationships.add(REL_RESPONSE);
        relationships.add(REL_SCREENSHOT);
        relationships.add(REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        Settings.Builder settings = Settings.builder();
        settings.timezone(Timezone.EUROPE_LONDON);
        settings.headless(true);
        settings.cache(true);
        settings.connectTimeout(
                context.getProperty(PROP_CONNECT_TIMEOUT)
                        .asTimePeriod(TimeUnit.MILLISECONDS)
                        .intValue()
        );
        settings.socketTimeout(
                context.getProperty(PROP_SOCKET_TIMEOUT)
                        .asTimePeriod(TimeUnit.MILLISECONDS)
                        .intValue()
        );
        settings.ssl("trustanything");
        settings.hostnameVerification(false);
        settings.blockAds(true);
        settings.ignoreDialogs(true);
        settings.screen(new Dimension(1920,1600));

        final String proxyHost = context.getProperty(PROP_PROXY_HOST).getValue();
        final Integer proxyPort = context.getProperty(PROP_PROXY_PORT).asInteger();

        if (proxyHost != null && proxyPort != null) {
            ProxyConfig proxy = new ProxyConfig(ProxyConfig.Type.HTTP, proxyHost, proxyPort);
            settings.proxy(proxy);
        }

        this.driver = new JBrowserDriver(settings.build());

        this.gson = new Gson();
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        FlowFile requestFlowFile = session.get();

        final ComponentLog logger = getLogger();

        final UUID txId = UUID.randomUUID();

        try {
            final URL url = new URL(trimToEmpty(context.getProperty(PROP_URL).evaluateAttributeExpressions(requestFlowFile).getValue()));
            logger.error(url.toString());

            final Boolean takeScreenshot = context.getProperty(PROP_SCREENSHOT).evaluateAttributeExpressions(requestFlowFile).asBoolean();

            this.driver.get(url.toString());

            // "//a[@data-q='reply-panel-reveal-btn']"
            // "//div[@class='reveal-number']": "click"

            if (this.driver.getStatusCode() == HttpURLConnection.HTTP_OK) {
                final String actionJson = trimToEmpty(context.getProperty(PROP_ACTIONS).evaluateAttributeExpressions(requestFlowFile).getValue());
                logger.error(actionJson);

                if (!actionJson.isEmpty()) {
                    try {
                        Map<String, String> actions = gson.fromJson(actionJson, HashMap.class);
                        for (String selector : actions.keySet()) {
                            List<WebElement> webElements = driver.findElements(By.xpath(selector));
                            if (!webElements.isEmpty()) {
                                WebElement webElement = webElements.get(1);
                                if (webElement != null) {
                                    switch (actions.get(selector)) {
                                        case "click":
                                            webElement.click();
                                            break;
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                }

                try {
                    InputStream ss = new ByteArrayInputStream(driver.getPageSource().getBytes(StandardCharsets.UTF_8));
                    requestFlowFile = session.importFrom(ss, requestFlowFile);
                    session.transfer(requestFlowFile, REL_RESPONSE);
                    ss.close();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }

                if (takeScreenshot) {
                    driver.executeScript("window.scrollTo(0, 0)");

                    byte[] screenshot = this.driver.getScreenshotAs(OutputType.BYTES);

                    try {
                        InputStream is = new ByteArrayInputStream(screenshot);
                        FlowFile screenshotFlowFile = session.create(requestFlowFile);
                        screenshotFlowFile = session.importFrom(is, screenshotFlowFile);
                        session.transfer(screenshotFlowFile, REL_SCREENSHOT);
                        is.close();
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            } else if (this.driver.getStatusCode() >= HttpURLConnection.HTTP_INTERNAL_ERROR) {
                // Server error - retryable
            } else {
                // Something else - not retryable
            }
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
        } finally {
        }
    }
}
