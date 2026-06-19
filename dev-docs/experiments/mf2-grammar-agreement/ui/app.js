const EXPRESSION_RE = /\{\$(?<arg>[A-Za-z_][\w.-]*)(?:\s+:(?<fn>[A-Za-z_][\w.-]*)(?<opts>[^{}]*))?\}/g;
const OPTION_RE = /(?<key>[A-Za-z_][\w.-]*)=(?:\$(?<ref>[A-Za-z_][\w.-]*)|(?<value>[^\s]+))/g;
const INPUT_RE = /\.input\s+\{\$(?<name>[A-Za-z_][\w.-]*)\s+:[^}]+\}/g;
const MATCH_RE = /\.match\s+(?<selectors>(?:\$[A-Za-z_][\w.-]*\s*)+)/;
const PLACEHOLDER_RE = /\{\$(?<name>[A-Za-z_][\w.-]*)\}/g;
const VARIANT_RE = /(?<keys>(?:\S+\s+)*?\S+)\s+\{\{(?<body>.*?)\}\}/g;

const swordForms = `.input {$usage :string}
.input {$number :string}
.input {$count :number}
.match $usage $number $count
bare singular * {{épée}}
bare plural * {{épées}}
definite singular * {{l'épée}}
definite plural * {{les épées}}
indefinite singular * {{une épée}}
indefinite plural * {{des épées}}
count * one {{une épée}}
count * other {{{$count} épées}}
* * * {{épée}}`;

const shieldForms = `.input {$usage :string}
.input {$case :string}
.input {$number :string}
.match $usage $case $number
bare * singular {{Schild}}
bare * plural {{Schilde}}
definite nominative singular {{der Schild}}
definite accusative singular {{den Schild}}
definite dative singular {{dem Schild}}
definite nominative plural {{die Schilde}}
definite accusative plural {{die Schilde}}
definite dative plural {{den Schilden}}
* * * {{Schild}}`;

const scenarios = [
  {
    id: "fr-term-mf2",
    title: "French term MF2: usage + number + count",
    messageId: "loot.found",
    args: { count: 3, item: "item.sword" },
    notes: [
      "The message only says it needs a count phrase.",
      "The term owns the MF2 form selector for bare, definite, indefinite, and counted usage.",
      "No elision heuristic is needed at runtime for this term; l'épée is explicitly selected by the term pattern.",
    ],
    bundle: {
      schema: "mf2-grammar-agreement/v0",
      locale: "fr",
      profile: "fr-v1",
      messages: {
        "loot.found": {
          value: "Vous avez trouvé {$count :count of=$item}.",
        },
      },
      terms: {
        "item.sword": {
          text: "épée",
          forms: { default: swordForms },
          morphology: {
            partOfSpeech: "noun",
            gender: "feminine",
            number: "singular",
          },
        },
      },
    },
  },
  {
    id: "fr-definite",
    title: "French term MF2: definite article",
    messageId: "inventory.pickup",
    args: { item: "item.sword" },
    notes: [
      "The message requests usage=definite through the :term option.",
      "The same term pattern returns l'épée for singular definite and les épées for plural definite.",
      "Metadata still helps the editor and validators, but the explicit form pattern is the rendered truth.",
    ],
    bundle: {
      schema: "mf2-grammar-agreement/v0",
      locale: "fr",
      profile: "fr-v1",
      messages: {
        "inventory.pickup": {
          value: "Vous avez ramassé {$item :term article=definite}.",
        },
      },
      terms: {
        "item.sword": {
          text: "épée",
          forms: { default: swordForms },
          morphology: {
            partOfSpeech: "noun",
            gender: "feminine",
            number: "singular",
          },
        },
      },
    },
  },
  {
    id: "de-case-mf2",
    title: "German term MF2: usage + case + number",
    messageId: "inventory.pickup",
    args: { item: "item.shield" },
    notes: [
      "German needs case; the term pattern has nominative, accusative, and dative rows.",
      "The message asks for accusative definite, so the term returns den Schild.",
      "This avoids pretending French-style gender/article flags are enough for German.",
    ],
    bundle: {
      schema: "mf2-grammar-agreement/v0",
      locale: "de",
      profile: "de-v1",
      messages: {
        "inventory.pickup": {
          value: "Du hast {$item :term article=definite case=accusative} aufgehoben.",
        },
      },
      terms: {
        "item.shield": {
          text: "Schild",
          forms: { default: shieldForms },
          morphology: {
            partOfSpeech: "noun",
            gender: "masculine",
            number: "singular",
          },
        },
      },
    },
  },
  {
    id: "kv-only",
    title: "KV-only resource store",
    messageId: "inventory.pickup",
    args: { item: "item.sword" },
    notes: [
      "The formatter reads by key; nested JSON is only the authoring shape.",
      "Android XML, Java properties, SQLite rows, or a binary pack can expose the same values.",
      "The important invariant is that the resolved term still contains text, forms.default, and optional metadata.",
    ],
    bundle: {
      schema: "mf2-grammar-agreement/v0",
      locale: "fr",
      profile: "fr-v1",
      messages: {
        "inventory.pickup": {
          value: "Vous avez ramassé {$item :term article=definite}.",
        },
      },
      terms: {
        "item.sword": {
          text: "épée",
          forms: { default: swordForms },
          morphology: {
            partOfSpeech: "noun",
            gender: "feminine",
            number: "singular",
          },
        },
      },
    },
  },
];

const appleComparison = {
  canInflect: { fr: true, de: true, ru: false, ar: false, ja: false, cy: false },
  rows: [
    {
      id: "fr épée definite",
      apple: "Vous avez ramassé épée.",
      ours: "Vous avez ramassé l'épée.",
    },
    {
      id: "fr bouclier definite",
      apple: "Vous avez ramassé bouclier.",
      ours: "Vous avez ramassé le bouclier.",
    },
    {
      id: "fr h aspiré",
      apple: "Vous avez ramassé héros.",
      ours: "Vous avez ramassé le héros.",
    },
    {
      id: "fr silent h",
      apple: "Vous avez réservé hôtel.",
      ours: "Vous avez réservé l'hôtel.",
    },
    {
      id: "de accusative Schild",
      apple: "Du hast den Schild aufgehoben.",
      ours: "Du hast den Schild aufgehoben.",
    },
  ],
  perf: [
    { runtime: "Apple Foundation", perOpUs: 68.5, note: "local macOS inflection probe" },
    { runtime: "Python prototype", perOpUs: 5.5, note: "raw term matcher prototype" },
    { runtime: "JavaScript prototype", perOpUs: 2.9, note: "raw term matcher prototype" },
    { runtime: "JS compiled matcher", perOpUs: 0.08, note: "precompiled sparse term matcher" },
  ],
};

function parseOptions(input) {
  const options = {};
  for (const match of input.matchAll(OPTION_RE)) {
    options[match.groups.key] = match.groups.ref ?? match.groups.value;
  }
  return options;
}

function flatten(data) {
  const output = {};
  const visit = (prefix, value) => {
    if (value && typeof value === "object" && !Array.isArray(value)) {
      for (const [key, child] of Object.entries(value)) visit(prefix ? `${prefix}/${key}` : key, child);
      return;
    }
    output[prefix] = value;
  };
  visit("", data);
  return output;
}

function runtimeValue(bundle, argName, argValue) {
  if (typeof argValue === "string" && bundle.terms[argValue]) {
    return { id: argValue, kind: "term", data: bundle.terms[argValue] };
  }
  return { id: argName, kind: "literal", data: { text: String(argValue) } };
}

function textOf(value) {
  return value.data.text;
}

function morphologyOf(value) {
  return value.data.morphology ?? {};
}

function formsOf(value) {
  return value.data.forms ?? {};
}

function optionContext(options, value) {
  const morphology = morphologyOf(value);
  const article = options.article ?? options.definiteness;
  return {
    usage: options.usage ?? (["definite", "indefinite", "partitive"].includes(article) ? article : "bare"),
    case: options.case ?? "*",
    number: options.number ?? morphology.number ?? "singular",
    count: options.count ?? "*",
    gender: morphology.gender ?? "*",
  };
}

function pluralKeyMatches(key, value) {
  if (key === "*") return true;
  if (/^\d+$/.test(key)) return String(value) === key;
  const count = Number(value);
  if (Number.isNaN(count)) return String(value) === key;
  if (key === "one") return count === 1;
  if (key === "other") return count !== 1;
  return false;
}

function keyMatches(key, selector, value) {
  if (key === "*") return true;
  if (selector === "count") return pluralKeyMatches(key, value);
  return String(value) === key;
}

function renderBody(body, context) {
  return body.replace(PLACEHOLDER_RE, (...args) => String(context[args.at(-1).name] ?? ""));
}

function formatTermMf2(pattern, context) {
  const selectorMatch = pattern.match(MATCH_RE);
  const selectors = selectorMatch.groups.selectors.trim().split(/\s+/).map((selector) => selector.slice(1));
  const declared = new Set([...pattern.matchAll(INPUT_RE)].map((match) => match.groups.name));
  const missing = selectors.filter((selector) => !declared.has(selector));
  if (missing.length) return `[[invalid term pattern: missing ${missing.join(", ")} input]]`;
  const variants = pattern.slice(selectorMatch.index + selectorMatch[0].length);
  let fallback = "";
  for (const variant of variants.matchAll(VARIANT_RE)) {
    const keys = variant.groups.keys.trim().split(/\s+/);
    if (keys.every((key) => key === "*")) fallback = variant.groups.body;
    if (keys.every((key, index) => keyMatches(key, selectors[index], context[selectors[index]] ?? "*"))) {
      return renderBody(variant.groups.body, context);
    }
  }
  return renderBody(fallback, context);
}

function format(bundle, messageId, args) {
  const pattern = bundle.messages[messageId].value;
  return pattern.replace(EXPRESSION_RE, (...replaceArgs) => {
    const groups = replaceArgs.at(-1);
    const value = runtimeValue(bundle, groups.arg, args[groups.arg]);
    if (!groups.fn) return textOf(value);
    return callFunction(bundle, groups.fn, value, parseOptions(groups.opts ?? ""), args);
  });
}

function resolveOptions(options, args) {
  return Object.fromEntries(
    Object.entries(options).map(([key, value]) => [
      key,
      !["with", "of"].includes(key) && Object.hasOwn(args, value) ? String(args[value]) : value,
    ]),
  );
}

function formatTerm(value, options) {
  if (formsOf(value).default) return formatTermMf2(formsOf(value).default, optionContext(options, value));
  return textOf(value);
}

function callFunction(bundle, fn, value, options, args) {
  const resolvedOptions = resolveOptions(options, args);
  if (fn === "term") return formatTerm(value, resolvedOptions);
  if (fn === "count") {
    const count = Number(textOf(value));
    const target = runtimeValue(bundle, resolvedOptions.of, args[resolvedOptions.of]);
    const number = count === 1 ? "singular" : "plural";
    if (formsOf(target).default) return formatTerm(target, { usage: "count", number, count: String(count) });
    return `${count} ${formatTerm(target, { number })}`;
  }
  return textOf(value);
}

function inferRequirements(bundle, messageId) {
  const pattern = bundle.messages[messageId].value;
  const requirements = {};
  for (const match of pattern.matchAll(EXPRESSION_RE)) {
    const arg = match.groups.arg;
    const fn = match.groups.fn;
    const options = parseOptions(match.groups.opts ?? "");
    if (fn === "term") {
      requirements[arg] = ["term.forms.default", "term.text", ...Object.keys(options).map((key) => `usage.${key}`)];
    }
    if (fn === "count" && options.of) {
      requirements[options.of] = ["term.forms.default", "term.text", "usage.count", "usage.number"];
    }
  }
  return requirements;
}

function selectScenario(id) {
  const scenario = scenarios.find((item) => item.id === id) ?? scenarios[0];
  const output = format(scenario.bundle, scenario.messageId, scenario.args);
  const terms = Object.fromEntries(
    Object.entries(scenario.args)
      .filter(([, value]) => typeof value === "string" && scenario.bundle.terms[value])
      .map(([arg, termId]) => [arg, { id: termId, ...scenario.bundle.terms[termId] }]),
  );

  document.querySelector("#formatted").textContent = output;
  document.querySelector("#profile").textContent = scenario.bundle.profile;
  document.querySelector("#pattern").textContent = scenario.bundle.messages[scenario.messageId].value;
  document.querySelector("#args").textContent = JSON.stringify(scenario.args, null, 2);
  document.querySelector("#terms").textContent = JSON.stringify(terms, null, 2);
  document.querySelector("#requirements").textContent = JSON.stringify(inferRequirements(scenario.bundle, scenario.messageId), null, 2);
  document.querySelector("#bundle").textContent = JSON.stringify(
    scenario.id === "kv-only" ? flatten(scenario.bundle) : scenario.bundle,
    null,
    2,
  );
  document.querySelector("#notes").innerHTML = scenario.notes.map((note) => `<li>${note}</li>`).join("");
  renderAppleComparison();
}

function renderAppleComparison() {
  document.querySelector("#apple-support").textContent = JSON.stringify(appleComparison.canInflect, null, 2);
  document.querySelector("#apple-rows").innerHTML = appleComparison.rows
    .map(
      (row) => `<tr>
        <td>${row.id}</td>
        <td>${row.apple}</td>
        <td>${row.ours}</td>
      </tr>`,
    )
    .join("");
  document.querySelector("#perf-rows").innerHTML = appleComparison.perf
    .map(
      (row) => `<tr>
        <td>${row.runtime}</td>
        <td>${row.perOpUs.toFixed(1)} us/op</td>
        <td>${row.note}</td>
      </tr>`,
    )
    .join("");
}

const select = document.querySelector("#scenario");
select.innerHTML = scenarios.map((scenario) => `<option value="${scenario.id}">${scenario.title}</option>`).join("");
select.addEventListener("change", () => selectScenario(select.value));
selectScenario(scenarios[0].id);
