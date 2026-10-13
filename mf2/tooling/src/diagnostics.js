// A single explicit truncation error makes capped validation fail closed.
export function reporter(diagnostics, maximum) {
  return (...items) => {
    for (const item of items) {
      if (diagnostics.length < maximum) diagnostics.push(item);
      else if (diagnostics.length === maximum)
        diagnostics.push({
          code: "diagnostic-limit",
          severity: "error",
          message:
            "Further diagnostics omitted; correct the reported issues and check again.",
        });
    }
  };
}
