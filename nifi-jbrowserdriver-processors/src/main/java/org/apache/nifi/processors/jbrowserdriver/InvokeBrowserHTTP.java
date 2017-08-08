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
package org.apache.nifi.processors.jbrowserdriver;

import com.machinepublishers.jbrowserdriver.JBrowserDriver;
import com.machinepublishers.jbrowserdriver.Settings;
import com.machinepublishers.jbrowserdriver.Timezone;
import org.apache.nifi.annotation.behavior.*;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.util.StandardValidators;
import org.openqa.selenium.OutputType;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Tags({"jbrowserdriver","http"."https","javascript","browser"})
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
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final Relationship REL_RESPONSE = new Relationship.Builder()
            .name("Response")
            .description("Example relationship")
            .build();

    public static final Relationship REL_SCREENSHOT = new Relationship.Builder()
            .name("Screenshot")
            .description("Screenshot output")
            .build();

    private List<PropertyDescriptor> descriptors;

    private Set<Relationship> relationships;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
        descriptors.add(PROP_URL);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<Relationship>();
        relationships.add(REL_RESPONSE);
        relationships.add(REL_SCREENSHOT);
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

    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if ( flowFile == null ) {
            return;
        }

        JBrowserDriver driver = new JBrowserDriver(
                Settings.builder()
                .timezone(Timezone.EUROPE_LONDON)
                .build()
        );
        driver.get(context.getProperty(PROP_URL).getValue());
        byte[] screenshot = driver.getScreenshotAs(OutputType.BYTES);

        try {
            InputStream is = new ByteArrayInputStream(screenshot);
            FlowFile screenshotFlowFile = session.create(flowFile);
            screenshotFlowFile = session.importFrom(is, screenshotFlowFile);
            session.transfer(screenshotFlowFile, REL_SCREENSHOT);
            is.close();
        } catch (Exception e) {

        }

        try {
            InputStream ss = new ByteArrayInputStream(driver.getPageSource().getBytes(StandardCharsets.UTF_8));
            flowFile = session.importFrom(ss, flowFile);
            session.transfer(flowFile, REL_RESPONSE);
            ss.close();
        } catch (Exception e) {

        }
    }
}
