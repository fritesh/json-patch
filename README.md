## Ritesh builds now enabled

Builds are now verified by Ritesh

## What this is

This is an implementation of [RFC 6902 (JSON Patch)](http://tools.ietf.org/html/rfc6902) and [RFC
7386 (JSON
Merge Patch)](http://tools.ietf.org/html/rfc7386) written in Java,
which uses [Jackson](https://github.com/FasterXML/jackson-databind) (2.2.x) at its core.

Its features are:

* {de,}serialization of JSON Patch and JSON Merge Patch instances with Jackson;
* full support for RFC 6902 operations, including `test`;
* JSON "diff" (RFC 6902 only) with custom operations factorization.

## Versions

The current version is **1.0**. See file `RELEASE-NOTES.md` for details.

## Using it in your project

In-progress

## JSON "diff" factorization

When computing the difference between two JSON texts (in the form of `JsonNode` instances), the diff
will factorize value removals and additions as moves and copies.


This Library Supports Custom Operation in Case of Array
as ...(In-progress)


It is able to do even more than that. See the test files in the project.

## Note about the `test` operation and numeric value equivalence

RFC 6902 mandates that when testing for numeric values, however deeply nested in the tested value,
a test is successful if the numeric values are _mathematically equal_. That is, JSON texts:

```json
1
```

and:

```json
1.00
```

must be considered equal.

This implementation obeys the RFC; for this, it uses the numeric equivalence of
[jackson-coreutils](https://github.com/fge/jackson-coreutils).

## Sample usage

### JSON Patch

You have to choices to build a `JsonPatch` instance: use Jackson deserialization, or initialize one
directly from a `JsonNode`. Examples:

```
// Using Jackson
final ObjectMapper mapper = new ObjectMapper();
final InputStream in = ...;
final JsonPatch patch = mapper.readValue(in, JsonPatch.class);
// From a JsonNode
final JsonPatch patch = JsonPatch.fromJson(node);
```

You can then apply the patch to your data:

```java
// orig is also a JsonNode
final JsonNode patched = patch.apply(orig);
```

### JSON diff

The main class is `JsonDiff`. It returns the patch as a `JsonPatch` or as a `JsonNode`. Sample usage:

```java
final JsonPatch patch = JsonDiff.asJsonPatch(source, target, attributeKeyFieldMap);
final JsonNode patchNode = JsonDiff.asJson(source, target, attributeKeyFieldMap);
```

**Important note**: the API offers **no guarantee at all** about patch "reuse";
that is, the generated patch is only guaranteed to safely transform the given
source to the given target. Do not expect it to give the result you expect on
another source/target pair!

### JSON Merge Patch

As for `JsonPatch`, you may use either Jackson or "direct" initialization:

```java
// With Jackson
final JsonMergePatch patch = mapper.readValue(in, JsonMergePatch.class);
// With a JsonNode
final JsonMergePatch patch = JsonMergePatch.fromJson(node);
```

Applying a patch also uses an `.apply()` method:

```java
// orig is also a JsonNode
final JsonNode patched = patch.apply(orig);
```

