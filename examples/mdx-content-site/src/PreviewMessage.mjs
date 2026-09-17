import React, { useId, useState } from "react";

// The site owns interaction. Mojito renders only the explicitly authored samples.
// The formatted sentence also labels its input; no untranslated control copy is
// hidden in component code. This example supports one number or ISO date input.
export function createPreviewMessage(format) {
  return function PreviewMessage({ resource, name, args }) {
    const initial = JSON.parse(args);
    const [values, setValues] = useState(initial);
    const outputId = useId();
    const entries = Object.entries(initial);
    const entry = entries.length === 1 ? entries[0] : null;
    const numeric = entry && typeof entry[1] === "number";
    const date =
      entry &&
      typeof entry[1] === "string" &&
      /^\d{4}-\d{2}-\d{2}T/.test(entry[1]);
    const field = entry?.[0];
    const input = numeric
      ? React.createElement("input", {
          type: "number",
          min: 0,
          max: 100,
          step: 1,
          value: values[field],
          "aria-labelledby": outputId,
          onChange(event) {
            const value = Number(event.target.value);
            if (
              event.target.value !== "" &&
              Number.isInteger(value) &&
              value >= 0 &&
              value <= 100
            )
              setValues({ [field]: value });
          },
        })
      : date
        ? React.createElement("input", {
            type: "date",
            value: values[field].slice(0, 10),
            "aria-labelledby": outputId,
            onChange(event) {
              if (/^\d{4}-\d{2}-\d{2}$/.test(event.target.value))
                setValues({ [field]: `${event.target.value}T12:00:00Z` });
            },
          })
        : null;
    return React.createElement(
      "div",
      { className: "preview-message", "data-message": name },
      React.createElement(
        "p",
        { id: outputId, "aria-live": "polite" },
        format(resource, name, values),
      ),
      input,
    );
  };
}
