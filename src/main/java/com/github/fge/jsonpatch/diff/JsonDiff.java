/*
 * Copyright (c) 2014, Francis Galiegue (fgaliegue@gmail.com)
 *
 * This software is dual-licensed under:
 *
 * - the Lesser General Public License (LGPL) version 3.0 or, at your option, any
 *   later version;
 * - the Apache Software License (ASL) version 2.0.
 *
 * The text of this file and of both licenses is available at the root of this
 * project or, if you have the jar distribution, in directory META-INF/, under
 * the names LGPL-3.0.txt and ASL-2.0.txt respectively.
 *
 * Direct link to the sources:
 *
 * - LGPL 3.0: https://www.gnu.org/licenses/lgpl-3.0.txt
 * - ASL 2.0: http://www.apache.org/licenses/LICENSE-2.0.txt
 */

package com.github.fge.jsonpatch.diff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.fge.jackson.JacksonUtils;
import com.github.fge.jackson.JsonNumEquals;
import com.github.fge.jackson.NodeType;
import com.github.fge.jackson.jsonpointer.JsonPointer;
import com.github.fge.jackson.jsonpointer.JsonPointerException;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.JsonPatchMessages;
import com.github.fge.msgsimple.bundle.MessageBundle;
import com.github.fge.msgsimple.load.MessageBundles;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import javax.annotation.ParametersAreNonnullByDefault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON "diff" implementation
 *
 * <p>This class generates a JSON Patch (as in, an RFC 6902 JSON Patch) given
 * two JSON values as inputs. The patch can be obtained directly as a {@link
 * JsonPatch} or as a {@link JsonNode}.</p>
 *
 * <p>Note: there is <b>no guarantee</b> about the usability of the generated
 * patch for any other source/target combination than the one used to generate
 * the patch.</p>
 *
 * <p>This class always performs operations in the following order: removals,
 * additions and replacements. It then factors removal/addition pairs into
 * move operations, or copy operations if a common element exists, at the same
 * {@link JsonPointer pointer}, in both the source and destination.</p>
 *
 * <p>You can obtain a diff either as a {@link JsonPatch} directly or, for
 * backwards compatibility, as a {@link JsonNode}.</p>
 *
 * @since 1.2
 */
@ParametersAreNonnullByDefault
public final class JsonDiff
{
    private static final MessageBundle BUNDLE
        = MessageBundles.getBundle(JsonPatchMessages.class);
    private static final ObjectMapper MAPPER = JacksonUtils.newMapper();
    private static Logger logger;
    private static final Equivalence<JsonNode> EQUIVALENCE
        = JsonNumEquals.getInstance();

    private JsonDiff()
    {
    	logger = LoggerFactory.getLogger(JsonDiff.class);
    }

    /**
     * Generate a JSON patch for transforming the source node into the target
     * node
     *
     * @param source the node to be patched
     * @param target the expected result after applying the patch
     * @return the patch as a {@link JsonPatch}
     *
     * @since 1.9
     */
    public static JsonPatch asJsonPatch(final JsonNode source,
        final JsonNode target)
    {
        BUNDLE.checkNotNull(source, "common.nullArgument");
        BUNDLE.checkNotNull(target, "common.nullArgument");
        final Map<JsonPointer, JsonNode> unchanged
            = getUnchangedValues(source, target);
        final DiffProcessor processor = new DiffProcessor(unchanged);

        generateDiffs(processor, JsonPointer.empty(), source, target);
        return processor.getPatch();
    }

    /**
     * Generate a JSON patch for transforming the source node into the target
     * node
     *
     * @param source the node to be patched
     * @param target the expected result after applying the patch
     * @return the patch as a {@link JsonNode}
     */
    public static JsonNode asJson(final JsonNode source, final JsonNode target)
    {
        final String s;
        try {
            s = MAPPER.writeValueAsString(asJsonPatch(source, target));
            return MAPPER.readTree(s);
        } catch (IOException e) {
            throw new RuntimeException("cannot generate JSON diff", e);
        }
    }
    
	private static void generateDiffs(final DiffProcessor processor,
        final JsonPointer pointer, final JsonNode source, final JsonNode target)
    {
        if (EQUIVALENCE.equivalent(source, target))
            return;

        final NodeType firstType = NodeType.getNodeType(source);
        final NodeType secondType = NodeType.getNodeType(target);

        /*
         * Node types differ: generate a replacement operation.
         */
        if (firstType != secondType) {
            processor.valueReplaced(pointer, source, target);
            return;
        }

        /*
         * If we reach this point, it means that both nodes are the same type,
         * but are not equivalent.
         *
         * If this is not a container, generate a replace operation.
         */
        if (!source.isContainerNode()) {
            processor.valueReplaced(pointer, source, target);
            return;
        }

        /*
         * If we reach this point, both nodes are either objects or arrays;
         * delegate.
         */
        if (firstType == NodeType.OBJECT)
            generateObjectDiffs(processor, pointer, (ObjectNode) source,
                (ObjectNode) target);
        else // array
            generateArrayDiffs(processor, pointer, (ArrayNode) source,
                (ArrayNode) target);
    }

    private static void generateObjectDiffs(final DiffProcessor processor,
        final JsonPointer pointer, final ObjectNode source,
        final ObjectNode target)
    {
        final Set<String> firstFields
            = Sets.newTreeSet(Sets.newHashSet(source.fieldNames()));
        final Set<String> secondFields
            = Sets.newTreeSet(Sets.newHashSet(target.fieldNames()));

        for (final String field: Sets.difference(firstFields, secondFields))
            processor.valueRemoved(pointer.append(field), source.get(field));

        for (final String field: Sets.difference(secondFields, firstFields))
            processor.valueAdded(pointer.append(field), target.get(field));

        for (final String field: Sets.intersection(firstFields, secondFields))
            generateDiffs(processor, pointer.append(field), source.get(field),
                target.get(field));
    }

    private static void generateArrayDiffs(final DiffProcessor processor,
        final JsonPointer pointer, final ArrayNode source,
        final ArrayNode target)
    {
        final int firstSize = source.size();
        final int secondSize = target.size();
        final int size = Math.min(firstSize, secondSize);

        /*
         * Source array is larger; in this case, elements are removed from the
         * target; the index of removal is always the original arrays's length.
         */
        for (int index = size; index < firstSize; index++)
            processor.valueRemoved(pointer.append(size), source.get(index));

        for (int index = 0; index < size; index++)
            generateDiffs(processor, pointer.append(index), source.get(index),
                target.get(index));

        // Deal with the destination array being larger...
        for (int index = size; index < secondSize; index++)
            processor.valueAdded(pointer.append("-"), target.get(index));
    }


    @VisibleForTesting
    static Map<JsonPointer, JsonNode> getUnchangedValues(final JsonNode source,
        final JsonNode target)
    {
        final Map<JsonPointer, JsonNode> ret = Maps.newHashMap();
        computeUnchanged(ret, JsonPointer.empty(), source, target);
        return ret;
    }

    private static void computeUnchanged(final Map<JsonPointer, JsonNode> ret,
        final JsonPointer pointer, final JsonNode first, final JsonNode second)
    {
        if (EQUIVALENCE.equivalent(first, second)) {
            ret.put(pointer, second);
            return;
        }

        final NodeType firstType = NodeType.getNodeType(first);
        final NodeType secondType = NodeType.getNodeType(second);

        if (firstType != secondType)
            return; // nothing in common

        // We know they are both the same type, so...

        switch (firstType) {
            case OBJECT:
                computeObject(ret, pointer, first, second);
                break;
            case ARRAY:
                computeArray(ret, pointer, first, second);
            default:
                /* nothing */
        }
    }

    private static void computeObject(final Map<JsonPointer, JsonNode> ret,
        final JsonPointer pointer, final JsonNode source,
        final JsonNode target)
    {
        final Iterator<String> firstFields = source.fieldNames();

        String name;

        while (firstFields.hasNext()) {
            name = firstFields.next();
            if (!target.has(name))
                continue;
            computeUnchanged(ret, pointer.append(name), source.get(name),
                target.get(name));
        }
    }

    private static void computeArray(final Map<JsonPointer, JsonNode> ret,

    		
    		
        final JsonPointer pointer, final JsonNode source, final JsonNode target)
    {
        final int size = Math.min(source.size(), target.size());

        for (int i = 0; i < size; i++)
            computeUnchanged(ret, pointer.append(i), source.get(i),
                target.get(i));
    }

	public static JsonNode asJson(final JsonNode source, final JsonNode target, Map<JsonPointer, String> attributesKeyFields)
			throws JsonPointerException {
		final String s;

		try {
			s = MAPPER.writeValueAsString(asJsonPatch(source, target, attributesKeyFields));
			return MAPPER.readTree(s);
		} catch (IOException e) {
			throw new RuntimeException("cannot generate JSON diff", e);
		}
	}

	public static JsonPatch asJsonPatch(final JsonNode source, final JsonNode target, Map<JsonPointer, String> attributesKeyFields)
			throws IOException, JsonPointerException {
		BUNDLE.checkNotNull(source, "common.nullArgument");
		BUNDLE.checkNotNull(target, "common.nullArgument");

		final Map<JsonPointer, JsonNode> unchanged = getUnchangedValues(source, target);
		final DiffProcessor processor = new DiffProcessor(unchanged);

		generateDiffs(processor, JsonPointer.empty(), source, target, attributesKeyFields);
		return processor.getPatch();
	}
	
	private static void generateDiffs(final DiffProcessor processor, final JsonPointer pointer, final JsonNode source, final JsonNode target,
			Map<JsonPointer, String> attributesKeyFields) throws IOException, JsonPointerException {

		if (EQUIVALENCE.equivalent(source, target))
			return;
		final NodeType firstType = NodeType.getNodeType(source);
		final NodeType secondType = NodeType.getNodeType(target);
		/*
		 * Node types differ: generate a replacement operation.
		 */
		
		//Handles Replace null with [] and [] with null type cases, when size is 0, we neglect this case 
		if(source.size() == 0 && target.size() == 0){
			if (firstType != secondType){ 
				return;
			}
		}
		
		//If type is different but size != 0 means replace complete object
		if (firstType != secondType) {
			processor.valueReplaced(pointer, source, target);
			return;
		}
		/*
		 * If we reach this point, it means that both nodes are the same type,
		 * but are not equivalent.
		 *
		 * If this is not a container, generate a replace operation.
		 */
		if (!source.isContainerNode()) {
			processor.valueReplaced(pointer, source, target);
			return;
		}
		/*
		 * If we reach this point, both nodes are either objects or arrays;
		 * delegate.
		 */
		if (firstType == NodeType.OBJECT) {

			generateObjectDiffs(processor, pointer, (ObjectNode) source, (ObjectNode) target, attributesKeyFields);

		} else {
			generateArrayDiffs(processor, pointer, (ArrayNode) source, (ArrayNode) target, attributesKeyFields);

		}
	}

	private static void generateObjectDiffs(final DiffProcessor processor, final JsonPointer pointer, final ObjectNode source,
			final ObjectNode target, Map<JsonPointer, String> attributeKeyFields) throws IOException, JsonPointerException {
		final Set<String> firstFields = Sets.newTreeSet(Sets.newHashSet(source.fieldNames()));
		final Set<String> secondFields = Sets.newTreeSet(Sets.newHashSet(target.fieldNames()));
		// this for loop is for calculating removed elements 

		for (final String field : Sets.difference(firstFields, secondFields)) {
			// Element To Remove 
			if ((source.get(field).size() != 0)) {
				//Source removal Array
				for (int index = 0; index < source.get(field).size(); index++) {
					//Multiple Array Element Removal
					processor.valueRemoved(pointer.append(field).append(index), source.get(field).get(index));
				}
			} else {
				//IF Empty Object Removal Do Nothing
				processor.valueRemoved(pointer.append(field), source.get(field));
			}
		}
		// this for loop is for calculating added elements 
		for (final String field : Sets.difference(secondFields, firstFields)) {
			//ADD Element
			processor.valueAdded(pointer.append(field), target.get(field));
		}
		// this for loop is for calculating common or value replaced Object Elements
		for (final String field : Sets.intersection(firstFields, secondFields)) {
			//REPLACE OR COMMON Elements
			generateDiffs(processor, pointer.append(field), source.get(field), target.get(field), attributeKeyFields);
		}
	}

	private static void generateArrayDiffs(final DiffProcessor processor, final JsonPointer pointer, final ArrayNode source, final ArrayNode target,
			final Map<JsonPointer, String> attributesKeyFields) throws IOException, JsonPointerException {

		final int sourceSize = source.size();
		final int targetSize = target.size();

		if (sourceSize < 1 && targetSize > 0) {
			//IF every element is ADD Element
			for (int i = 0; i < targetSize; i++) {
				processor.valueAdded(pointer.append("-"), target.get(i));
			}
		} else if (sourceSize > 0 && targetSize < 1) {
			// IF Every Element is Remove Element
			for (int k = 0; k < sourceSize; k++) {
				processor.arrayObjectValueRemoved(pointer.append(k), source.get(k));
			}
		} else {
			// Few Added, Few Removed Elements
			final NodeType firstType = NodeType.getNodeType(source.get(0));

			if (attributesKeyFields.containsKey(pointer) || firstType.equals(NodeType.STRING)) {
				//It is an Array of Object with KEY or an Array of String with key as NULL
				String keyFieldValue = attributesKeyFields.get(pointer);
				if (keyFieldValue == null) {
					//It is Array Of String

					List<JsonNode> targetList = new ArrayList<JsonNode>();
					List<JsonNode> sourceList = new ArrayList<JsonNode>();

					for (int j = 0; j < targetSize; j++) {
						// add All Target Elements to TargetList
						targetList.add(target.get(j));
					}
					for (int i = 0; i < sourceSize; i++) {
						if (!targetList.contains(source.get(i))) {
							// if source contains elements that are not in targetList add them to sourceList i.e list of Deleted String
							sourceList.add(source.get(i));
						} else {
							// if source contains elements that are present in targetList then remove from targetList i.e. at the end of for-loop we will get list of added object in targetList
							targetList.remove(source.get(i));
						}
					}
					//Remove String that is in SourceList
					for (int k = 0; k < sourceList.size(); k++) {
						if (sourceList.contains(source.get(k))) {
							processor.arrayObjectValueRemoved(pointer.append(k), source.get(k));
						}
					}

					//Add Strings that are in target List 
					for (int l = 0; l < targetList.size(); l++) {
						if (targetList.contains(source.get(l))) {
							processor.valueAdded(pointer.append("-"), target.get(l));
						}
					}
				} else {
					//Array Object without Key Field
					List<String> targetObjectList = new ArrayList<String>();
					List<String> objectToRemoveList = new ArrayList<String>();
					// get all Primary Key values from target to a List
					for (int j = 0; j < targetSize; j++) {
						targetObjectList.add(target.get(j).get(keyFieldValue).asText());
					}
					for (int j = 0; j < sourceSize; j++) {
						//Comparing Each source Object with Target Objects  //only Key Comparison
						objectToRemoveList = (generateObjectInArrayDiffs(processor, pointer.append(j), source.get(j), target, targetObjectList,
								keyFieldValue, objectToRemoveList, attributesKeyFields));
					}
					targetObjectList.removeAll(objectToRemoveList);
					for (int i = 0; i < targetSize; i++) {
						if (targetObjectList.contains(target.get(i).get(keyFieldValue).asText())) {
							// After Evaluating all Now we do the remaining addition	
							processor.valueAdded(pointer.append("-"), target.get(i));
						}
					}
					targetObjectList.clear();
				}
			} else {
				logger.warn("WARNING : Key Field Not Available for Pointer at  : {}", pointer);
			}

		}
	}

	public static List<String> generateObjectInArrayDiffs(final DiffProcessor processor, JsonPointer pointer, final JsonNode source,
			final JsonNode target, final List<String> targetObjectList, final String keyFieldValue, final List<String> objectToRemoveList,
			final Map<JsonPointer, String> attributesKeyFields) throws IOException, JsonPointerException {
		int targetSize = target.size();
		// check weather the key field matches
		if (targetObjectList.contains(source.get(keyFieldValue).asText())) {
			// Key Matched
			objectToRemoveList.add(source.get(keyFieldValue).asText());
			// check if internal Field Matches
			for (int i = 0; i < targetSize; i++) {
				if (target.get(i).get(keyFieldValue).equals(source.get(keyFieldValue))) {
					if (!target.get(i).equals(source)) {
						//If Content at Source and Target Does not Matches Sending Data For Replace Operation
						generateCustomDiffs(processor, pointer, source, target.get(i));
					}
				}
			}
		} else {
			// if here means removed from source then perform remove operation
			processor.arrayObjectValueRemoved(pointer, source);
		}
		return objectToRemoveList;
	}

	public static void generateCustomDiffs(final DiffProcessor processor, JsonPointer pointer, final JsonNode source, final JsonNode target) {
		final Set<String> sourceFields = Sets.newTreeSet(Sets.newHashSet(source.fieldNames()));
		final Set<String> targetFields = Sets.newTreeSet(Sets.newHashSet(target.fieldNames()));
		for (String field : sourceFields) {
			if (!(source.get(field).equals(target.get(field)))) {
				processor.arrayObjectValueReplaced(pointer.append(field), source, target.get(field));
			}
		}
		for (final String field : Sets.difference(targetFields, sourceFields)) {
			processor.arrayObjectValueReplaced(pointer.append(field), source, target.get(field));
		}
	}



}
