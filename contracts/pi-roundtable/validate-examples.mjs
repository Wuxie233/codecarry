import Ajv from "ajv";
import addFormats from "ajv-formats";
import fs from "node:fs";
import path from "node:path";

const root = new URL(".", import.meta.url).pathname;
const ajv = new Ajv({ strict: false, allErrors: true });
addFormats(ajv);

for (const file of fs.readdirSync(path.join(root, "schema")).filter((name) => name.endsWith(".json"))) {
  const schemaPath = path.join(root, "schema", file);
  const schema = JSON.parse(fs.readFileSync(schemaPath, "utf8"));
  try {
    ajv.addSchema(schema);
  } catch (error) {
    if (!String(error?.message || error).includes("already exists")) throw error;
  }
}

const targets = [
  ["roundtable-sse-event.json", /^event-.*\.json$/],
  ["command.json", /^command-.*\.json$/],
  ["catalog.json", /^catalog\.json$/],
  ["persona.json", /^persona\.json$/],
  ["transcript.json", /^transcript\.json$/],
];

let failures = 0;
for (const [schemaName, pattern] of targets) {
  const validate = ajv.getSchema(`https://schemas.oc-remote.local/pi-roundtable/v1/${schemaName}`);
  if (!validate) throw new Error(`Missing schema ${schemaName}`);
  for (const file of fs.readdirSync(path.join(root, "examples")).filter((name) => pattern.test(name)).sort()) {
    const examplePath = path.join(root, "examples", file);
    const data = JSON.parse(fs.readFileSync(examplePath, "utf8"));
    if (validate(data)) {
      console.log(`examples/${file} valid`);
    } else {
      failures += 1;
      console.log(`examples/${file} invalid`);
      console.log(JSON.stringify(validate.errors, null, 2));
    }
  }
}

process.exitCode = failures === 0 ? 0 : 1;
