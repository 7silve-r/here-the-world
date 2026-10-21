import Ajv2020 from "ajv/dist/2020";
import addFormats from "ajv-formats";
import specification from "../../../docs/api/openapi.json";
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
ajv.addSchema({ $id: "htw", ...specification });
export const operations = Object.entries(specification.paths).flatMap(
  ([path, methods]) =>
    Object.entries(methods).map(([method, operation]) => ({
      path,
      method: method.toUpperCase(),
      operation,
      regex: new RegExp(`^${path.replace(/\{[^}]+\}/g, "([^/]+)")}$`),
      parameters: [...path.matchAll(/\{([^}]+)\}/g)].map((m) => m[1]),
    })),
);
const validators = new WeakMap<object, ReturnType<typeof ajv.compile>>();
export function validator(schema: unknown) {
  const key = schema as object;
  if (validators.has(key)) return validators.get(key)!;
  const result = ajv.compile({ ...key, components: specification.components });
  validators.set(key, result);
  return result;
}
export function validateBody(schema: unknown, value: unknown) {
  const check = validator(schema);
  return check(value)
    ? []
    : check.errors!.map((e) => ({
        field:
          e.instancePath.slice(1) ||
          (e.params.missingProperty as string) ||
          null,
        reason: "INVALID_FORMAT",
        message: e.message || "输入无效",
      }));
}
