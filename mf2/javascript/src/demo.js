import { formatMessage, formatMessageToParts, parseToModel } from "./index.js";

const catalog = {
  welcome: "Welcome, {$name}!",
  "cart.items": `.input {$count :number}
.match $count
one {{{$count} item}}
* {{{$count} items}}`,
  "assignee.files": `.input {$gender :string}
.input {$count :number}
.match $gender $count
male one {{He reviewed {$count} file}}
female one {{She reviewed {$count} file}}
male * {{He reviewed {$count} files}}
female * {{She reviewed {$count} files}}
* * {{They reviewed {$count} files}}`,
  "rich.link": "Tap {#link href=$url @title=|Profile page|}profile{/link}. {$name :string @kind=person}",
};

for (const [id, source] of Object.entries(catalog)) {
  const parsed = parseToModel(source);
  if (parsed.hasDiagnostics) {
    console.log(`${id} -> parser diagnostics`, parsed.diagnostics);
    continue;
  }
  const args = demoArguments(id);
  console.log(`${id} -> ${JSON.stringify(formatMessage(parsed.model, args, { locale: "en" }))}`);
  if (id === "rich.link") {
    console.log(`${id} parts -> ${JSON.stringify(formatMessageToParts(parsed.model, args, { locale: "en" }))}`);
  }
}

function demoArguments(id) {
  switch (id) {
    case "welcome":
      return { name: "Mojito" };
    case "cart.items":
      return { count: 2 };
    case "assignee.files":
      return { gender: "female", count: 3 };
    case "rich.link":
      return { name: "Jean", url: "/people/jean" };
    default:
      return {};
  }
}
