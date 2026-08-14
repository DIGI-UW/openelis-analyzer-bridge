#!/usr/bin/env node

import { createHash } from "node:crypto";
import { mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const legacyRoot = path.resolve(process.argv[2] || "");
const outputRoot = path.join(
  repositoryRoot,
  "src/main/resources/analyzer-profiles",
);
const reportPath = path.join(
  repositoryRoot,
  "contracts/analyzer/v1/profile-migration-report.json",
);
const protocolDirectories = ["astm", "hl7", "file"];

if (!process.argv[2]) {
  throw new Error(
    "Usage: scripts/migrate-legacy-profiles.mjs <legacy-profile-root>",
  );
}

const digest = (value) => createHash("sha256").update(value).digest("hex");
const stableKey = (prefix, profileId, index, sourceValue) =>
  `${prefix}-${String(index + 1).padStart(3, "0")}-${digest(`${profileId}\0${index}\0${sourceValue}`).slice(0, 12)}`;

const portableResultType = (mapping) => {
  const declared = String(mapping.result_type || "").toLowerCase();
  if (declared === "qualitative") return "QUALITATIVE";
  if (declared === "quantitative" || declared === "numeric") return "NUMERIC";
  if (Array.isArray(mapping.values) && mapping.values.length > 0)
    return "QUALITATIVE";
  return "UNKNOWN";
};

const canonicalColumnSemantic = (semantic) =>
  semantic === "unit" ? "units" : semantic;

const filePattern = (extensions) => {
  const suffixes = extensions.map((extension) =>
    extension.replace(/^\./, "").replace(/[^a-zA-Z0-9]/g, ""),
  );
  return `(?i).*\\.(${suffixes.join("|")})$`;
};

const convertProfile = (legacy, sourcePath, sourceContent) => {
  const profileId = legacy.profileMeta.id;
  const protocol = legacy.protocol.name;
  const fileFormat = legacy.protocol.format;
  const unsupportedRuntimeFormat = protocol === "FILE" && fileFormat === "XML";
  const sourceMappings = legacy.default_test_mappings || [];
  const sourceQcRules = legacy.configDefaults?.qcRules || [];
  const issues = [];

  if (unsupportedRuntimeFormat) {
    issues.push({
      code: "UNSUPPORTED_RUNTIME_FORMAT",
      detail:
        "Bridge FILE runtime does not parse XML; profile is shipped inactive until a tested parser exists.",
    });
  }

  const unspecifiedResultRows = sourceMappings.filter(
    (mapping) =>
      !mapping.result_type &&
      !(Array.isArray(mapping.values) && mapping.values.length > 0),
  ).length;
  if (unspecifiedResultRows > 0) {
    issues.push({
      code: "RESULT_TYPE_UNSPECIFIED",
      count: unspecifiedResultRows,
      detail:
        "Legacy rows without a declared result type remain UNKNOWN instead of inferring clinical semantics.",
    });
  }

  const profile = {
    schemaVersion: "1.0",
    profileId,
    revision: 1,
    displayName: legacy.profileMeta.displayName,
    category: legacy.category,
    confidence: legacy.profileMeta.confidence,
    legacyVersion: legacy.profileMeta.version,
    source: "SHIPPED",
    status: unsupportedRuntimeFormat ? "INACTIVE" : "ACTIVE",
    protocol,
    capabilities: {
      inboundResults: true,
      outboundOrders: Boolean(legacy.communication?.supports_lis_initiated),
      connectionTest: !unsupportedRuntimeFormat,
    },
    tests: sourceMappings.map((mapping, index) => {
      const test = {
        sourceRowKey: stableKey("test", profileId, index, mapping.test_code),
        analyzerCode: mapping.test_code,
        displayName: mapping.test_name_hint || mapping.test_code,
        resultType: portableResultType(mapping),
        normalizedCoding: {
          system: "http://loinc.org",
          code: mapping.loinc,
          ...(mapping.test_name_hint
            ? { display: mapping.test_name_hint }
            : {}),
        },
        resultValues: (mapping.values || []).map((rawValue) => ({
          rawValue,
          displayName: rawValue,
        })),
      };
      if (mapping.unit) test.unit = mapping.unit;
      return test;
    }),
    qcIdentification: sourceQcRules
      .filter((rule) => rule.isActive !== false)
      .map((rule, index) => ({
        ruleKey: stableKey(
          "qc",
          profileId,
          index,
          `${rule.ruleType}:${rule.targetField || ""}:${rule.operand}`,
        ),
        ruleType: rule.ruleType,
        ...(rule.targetField ? { targetField: rule.targetField } : {}),
        operand: rule.operand,
      })),
  };

  const manufacturer = legacy.manufacturer || legacy.profileMeta.manufacturer;
  if (manufacturer || legacy.identifier_pattern) {
    profile.identity = {
      ...(legacy.identifier_pattern
        ? { senderPattern: legacy.identifier_pattern }
        : {}),
      ...(manufacturer ? { manufacturer } : {}),
    };
  }

  if (protocol === "FILE") {
    profile.file = {
      format: fileFormat,
      filePattern: filePattern(legacy.supported_extensions),
      columnMappings: Object.fromEntries(
        Object.entries(legacy.column_mapping).map(([column, semantic]) => [
          column,
          canonicalColumnSemantic(semantic),
        ]),
      ),
      ...(legacy.configDefaults?.delimiter
        ? { delimiter: legacy.configDefaults.delimiter }
        : {}),
      ...(Number.isInteger(legacy.configDefaults?.skipRows)
        ? { skipRows: legacy.configDefaults.skipRows }
        : {}),
    };
  }

  return {
    profile,
    report: {
      sourcePath,
      sourceSha256: digest(sourceContent),
      sourceTestRows: sourceMappings.length,
      portableTestRows: profile.tests.length,
      sourceQcRows: sourceQcRules.length,
      portableQcRows: profile.qcIdentification.length,
      status: profile.status,
      issues,
    },
  };
};

const report = {
  schemaVersion: "1.0",
  generatedBy: "scripts/migrate-legacy-profiles.mjs",
  sourceAuthority: "OpenELIS transitional analyzer profile catalog",
  targetAuthority: "Analyzer Bridge portable profile catalog",
  summary: {
    profileCount: 0,
    sourceTestRows: 0,
    portableTestRows: 0,
    sourceQcRows: 0,
    portableQcRows: 0,
    inactiveProfiles: 0,
  },
  profiles: {},
};

for (const protocolDirectory of protocolDirectories) {
  const sourceDirectory = path.join(legacyRoot, protocolDirectory);
  const filenames = (await readdir(sourceDirectory))
    .filter((name) => name.endsWith(".json"))
    .sort();
  const targetDirectory = path.join(outputRoot, protocolDirectory);
  await mkdir(targetDirectory, { recursive: true });

  for (const filename of filenames) {
    const absoluteSource = path.join(sourceDirectory, filename);
    const sourceContent = await readFile(absoluteSource, "utf8");
    const legacy = JSON.parse(sourceContent);
    const sourcePath = path.posix.join(protocolDirectory, filename);
    const converted = convertProfile(legacy, sourcePath, sourceContent);
    const target = path.join(targetDirectory, filename);
    await writeFile(
      target,
      `${JSON.stringify(converted.profile, null, 2)}\n`,
      "utf8",
    );
    report.profiles[converted.profile.profileId] = converted.report;
    report.summary.profileCount += 1;
    report.summary.sourceTestRows += converted.report.sourceTestRows;
    report.summary.portableTestRows += converted.report.portableTestRows;
    report.summary.sourceQcRows += converted.report.sourceQcRows;
    report.summary.portableQcRows += converted.report.portableQcRows;
    if (converted.profile.status === "INACTIVE")
      report.summary.inactiveProfiles += 1;
  }
}

report.profiles = Object.fromEntries(
  Object.entries(report.profiles).sort(([left], [right]) =>
    left.localeCompare(right),
  ),
);
await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");

console.log(
  `Migrated ${report.summary.profileCount} profiles, ${report.summary.portableTestRows} test rows, ` +
    `${report.summary.portableQcRows} QC rows; ${report.summary.inactiveProfiles} profile inactive.`,
);
