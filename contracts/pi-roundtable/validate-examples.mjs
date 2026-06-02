import Ajv from "ajv";
import addFormats from "ajv-formats";
import crypto from "node:crypto";
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

const eventSchema = ajv.getSchema("https://schemas.oc-remote.local/pi-roundtable/v1/roundtable-sse-event.json");
if (!eventSchema) throw new Error("Missing schema roundtable-sse-event.json");

const fixturesDir = path.join(root, "fixtures");
if (fs.existsSync(fixturesDir)) {
  for (const file of fs.readdirSync(fixturesDir).filter((name) => name.endsWith(".json")).sort()) {
    const fixturePath = path.join(fixturesDir, file);
    const data = JSON.parse(fs.readFileSync(fixturePath, "utf8"));
    const events = Array.isArray(data) ? data : data.events;
    if (!Array.isArray(events)) {
      failures += 1;
      console.log(`fixtures/${file} invalid`);
      console.log("fixture must be an array or an object with events[]");
      continue;
    }

    let fixtureValid = true;
    for (const [index, event] of events.entries()) {
      if (!eventSchema(event)) {
        failures += 1;
        fixtureValid = false;
        console.log(`fixtures/${file}[${index}] invalid`);
        console.log(JSON.stringify(eventSchema.errors, null, 2));
      }
    }

    if (fixtureValid && !validateFixtureReassembly(file, events)) {
      failures += 1;
      fixtureValid = false;
    }

    if (fixtureValid) console.log(`fixtures/${file} valid`);
  }
}

function validateFixtureReassembly(file, events) {
  const seenByEventId = new Map();
  const accepted = [];
  for (const event of events) {
    const duplicate = seenByEventId.get(event.eventId);
    if (duplicate) {
      if (JSON.stringify(duplicate) !== JSON.stringify(event)) {
        console.log(`fixtures/${file} invalid`);
        console.log(`eventId ${event.eventId} is reused with a different event body`);
        return false;
      }
      continue;
    }
    seenByEventId.set(event.eventId, event);
    accepted.push(event);
  }

  accepted.sort((left, right) => left.sequence - right.sequence || left.eventId - right.eventId);

  const turns = new Map();
  for (const event of accepted) {
    if (event.type === "message_delta") {
      const turn = getTurn(turns, event.turnId);
      const existing = turn.deltas.get(event.payload.deltaIndex);
      if (existing !== undefined && existing !== event.payload.chunk) {
        console.log(`fixtures/${file} invalid`);
        console.log(`${event.turnId} deltaIndex ${event.payload.deltaIndex} has conflicting chunks`);
        return false;
      }
      turn.deltas.set(event.payload.deltaIndex, event.payload.chunk);
    }

    if (event.type === "message_end") {
      const turn = getTurn(turns, event.turnId);
      const orderedChunks = [...turn.deltas.entries()].sort((left, right) => left[0] - right[0]);
      if (orderedChunks.length !== event.payload.deltaCount) {
        console.log(`fixtures/${file} invalid`);
        console.log(`${event.turnId} expected ${event.payload.deltaCount} deltas, saw ${orderedChunks.length}`);
        return false;
      }
      const finalText = orderedChunks.map(([, chunk]) => chunk).join("");
      if (event.payload.finalText !== undefined && event.payload.finalText !== finalText) {
        console.log(`fixtures/${file} invalid`);
        console.log(`${event.turnId} finalText does not match joined deltas`);
        return false;
      }
      if (event.payload.contentSha256 !== undefined) {
        const digest = crypto.createHash("sha256").update(finalText, "utf8").digest("hex");
        if (event.payload.contentSha256 !== digest) {
          console.log(`fixtures/${file} invalid`);
          console.log(`${event.turnId} contentSha256 does not match joined deltas`);
          return false;
        }
      }
    }
  }
  return true;
}

function getTurn(turns, turnId) {
  if (!turnId) throw new Error("message event missing turnId");
  if (!turns.has(turnId)) turns.set(turnId, { deltas: new Map() });
  return turns.get(turnId);
}

process.exitCode = failures === 0 ? 0 : 1;
