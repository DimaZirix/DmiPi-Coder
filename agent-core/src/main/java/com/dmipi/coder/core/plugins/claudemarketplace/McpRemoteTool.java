package com.dmipi.coder.core.plugins.claudemarketplace;

/** One tool advertised by an MCP server: remote name, description, raw JSON input schema, read-only hint. */
record McpRemoteTool(String name, String description, String inputSchemaJson, boolean readOnly) {
}
