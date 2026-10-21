import { readFileSync, writeFileSync } from "node:fs";
const spec = JSON.parse(
  readFileSync(new URL("../../docs/api/openapi.json", import.meta.url), "utf8"),
);
const type = (s) => {
  if (s.$ref) return s.$ref.split("/").at(-1);
  if (s.const !== undefined) return JSON.stringify(s.const);
  if (s.enum) return s.enum.map(JSON.stringify).join(" | ");
  if (s.anyOf || s.oneOf) return (s.anyOf || s.oneOf).map(type).join(" | ");
  if (s.type === "array") return `Array<${type(s.items)}>`;
  if (s.type === "object") {
    const props = Object.entries(s.properties || {})
      .map(
        ([k, v]) =>
          `${JSON.stringify(k)}${s.required?.includes(k) ? "" : "?"}: ${type(v)}`,
      )
      .join("; ");
    return `{ ${props}${s.additionalProperties ? `${props ? "; " : ""}[key: string]: ${typeof s.additionalProperties === "object" ? type(s.additionalProperties) : "unknown"}` : ""} }`;
  }
  return (
    {
      integer: "number",
      number: "number",
      boolean: "boolean",
      null: "null",
      string: "string",
    }[s.type] || "unknown"
  );
};
let output = `// Generated from docs/api/openapi.json (${spec.info.version}). DO NOT EDIT.\n`;
for (const [name, schema] of Object.entries(spec.components.schemas))
  output += `export type ${name} = ${type(schema)};\n`;
output += "export interface Operations {\n";
for (const [path, item] of Object.entries(spec.paths))
  for (const [method, op] of Object.entries(item)) {
    const response = Object.entries(op.responses).find(
      ([status]) => +status >= 200 && +status < 300,
    )[1].content["application/json"].schema.properties.data;
    output += `${op.operationId}: { method: ${JSON.stringify(method.toUpperCase())}; path: ${JSON.stringify(path)}; input: ${op.requestBody ? type(op.requestBody.content["application/json"].schema) : "undefined"}; output: ${type(response)} };\n`;
  }
output += "}\n";
output += `export const endpoints = ${JSON.stringify(Object.fromEntries(Object.entries(spec.paths).flatMap(([path, methods]) => Object.entries(methods).map(([method, op]) => [op.operationId, { path, method: method.toUpperCase() }]))), null, 2)} as const;\n`;
const target = new URL("../src/api/generated.ts", import.meta.url);
if (process.argv.includes("--check")) {
  if (readFileSync(target, "utf8").replace(/\r\n/g, "\n") !== output)
    throw new Error("API 类型过期，请执行 npm run generate:api");
} else writeFileSync(target, output);
