/**
 * 
 */
package com.github.fge.jsonpatch.diffcustom;

import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jackson.jsonpointer.JsonPointerException;
import com.github.fge.jsonpatch.JsonPatchException;
import com.github.fge.jsonpatch.diff.JsonDiff;

/**
 * @author ruchika
 *
 */
public class TestDiffGenerator {
	private ObjectMapper objectMapper = new ObjectMapper();
	private Logger logger = LoggerFactory.getLogger(TestDiffGenerator.class);
	private JsonNode patch;

	@Test(priority = 1)
	public void testAddFieldOperation()
			throws JsonProcessingException, IOException, JsonPatchException, JsonPointerException {

		JsonNode oldJson = objectMapper.readTree(new File("src/test/resources/old.json"));
		JsonNode newJson = objectMapper.readTree(new File("src/test/resources/new.json"));

		patch = JsonDiff.asJson(oldJson, newJson, null);
		logger.info("Aftercalculating diff:   {} ", patch);

	}

}
