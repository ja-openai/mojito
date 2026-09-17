import { createProcessor } from "@mdx-js/mdx";
import path from "node:path";

const parser = createProcessor();

// Parse without evaluating. Only prose, segment comments and static MDX includes
// are allowed through to Vite; translated text must never become JavaScript.
export function structure(source, file) {
  const tree = parser.parse(source);
  const imports = [];
  for (const node of tree.children) {
    if (node.type !== "mdxjsEsm") continue;
    for (const statement of node.data.estree.body) {
      const binding = statement.specifiers?.[0];
      if (
        statement.type !== "ImportDeclaration" ||
        statement.specifiers.length !== 1 ||
        binding.type !== "ImportDefaultSpecifier" ||
        !/^[A-Z]\w*$/.test(binding.local.name)
      ) {
        throw new Error(`Only default MDX imports are supported: ${file}`);
      }
      const specifier = statement.source.value;
      if (
        !/^\.\.?\//.test(specifier) ||
        !specifier.endsWith(".mdx") ||
        /[\\%?#:\x00-\x1f]/.test(specifier)
      ) {
        throw new Error(`Invalid module import: ${file}`);
      }
      const resolved = path.posix.normalize(
        path.posix.join(path.posix.dirname(file), specifier),
      );
      if (resolved.startsWith("../") || resolved.startsWith("/"))
        throw new Error(`Module escapes content directory: ${file}`);
      imports.push([binding.local.name, resolved]);
    }
  }
  const components = [];
  const pending = [...tree.children].reverse();
  while (pending.length) {
    const node = pending.pop();
    if (node.type === "mdxjsEsm" && !tree.children.includes(node)) {
      throw new Error(`Only top-level MDX imports are supported: ${file}`);
    }
    if (
      node.type === "mdxFlowExpression" ||
      node.type === "mdxTextExpression"
    ) {
      if (!/^\s*\/\*\s*mojito-id:\s*[\w.:-]+\s*\*\/\s*$/.test(node.value)) {
        throw new Error(`Executable expressions are not supported: ${file}`);
      }
    } else if (
      node.type === "mdxJsxFlowElement" ||
      node.type === "mdxJsxTextElement"
    ) {
      if (
        !imports.some(([name]) => name === node.name) ||
        node.attributes.length ||
        node.children.length
      ) {
        throw new Error(
          `Only empty imported MDX components are supported: ${file}`,
        );
      }
      components.push(node.name);
    }
    if (
      node.url &&
      (/[\x00-\x20]/.test(node.url) ||
        (/^[a-z][a-z\d+.-]*:/i.test(node.url) &&
          !/^(https?:|mailto:)/i.test(node.url)))
    ) {
      throw new Error(`Unsupported link URL: ${file}`);
    }
    pending.push(...(node.children ?? []).slice().reverse());
  }
  return { imports, components };
}

export function validateModules(structures) {
  for (const file of structures.keys()) {
    const pending = [{ file, ancestors: [] }];
    while (pending.length) {
      const current = pending.pop();
      if (current.ancestors.includes(current.file))
        throw new Error(`Module cycle: ${current.file}`);
      if (current.ancestors.length > 3)
        throw new Error(`Module depth exceeds 3: ${current.file}`);
      const definition = structures.get(current.file);
      if (!definition) throw new Error(`Missing module: ${current.file}`);
      for (const [, child] of definition.imports) {
        pending.push({
          file: child,
          ancestors: [...current.ancestors, current.file],
        });
      }
    }
  }
}
