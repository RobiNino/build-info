package org.jfrog.build.extractor.xrayScanViolationsTable;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jfrog.build.client.artifactoryXrayResponse.ArtifactoryXrayResponse;
import org.jfrog.build.extractor.util.TestingLog;
import org.testng.Assert;
import org.testng.Reporter;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;

public class XrayViolationsTableHelperTest {
    private static final String BASE_CONFIG_PATH = "/xrayScanViolationsTable/scanResult.json";
    private final XrayViolationsTableHelper tableHelper = new XrayViolationsTableHelper();

    @Test
    public void testPrintTable() throws IOException, URISyntaxException {
        ArtifactoryXrayResponse result = getXrayResultResource();

        tableHelper.PrintTable(result, new TestingLog());
        List<String> out = Reporter.getOutput();
        Assert.assertEquals(out.size(), 15);
        Assert.assertEquals(out.get(0), tableHelper.TABLE_HEADLINE);
        String headersLine = out.get(1);
        for (String header : tableHelper.TABLE_HEADERS) {
            Assert.assertTrue(headersLine.contains(header));
        }
        Assert.assertEquals(out.get(3).length(), out.get(4).length());
    }

    private ArtifactoryXrayResponse getXrayResultResource() throws URISyntaxException, IOException {
        File testResourcesPath = new File(this.getClass().getResource(BASE_CONFIG_PATH).toURI()).getCanonicalFile();
        ObjectMapper mapper = new ObjectMapper(new JsonFactory());
        return mapper.readValue(testResourcesPath, ArtifactoryXrayResponse.class);
    }
}
