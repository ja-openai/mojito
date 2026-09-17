import React, { useState } from "react";

// A site-owned adapter for the same static choice wrapper understood by review.
// Both alternatives are compiled; the browser only chooses which panel to show.
export function createPreviewChoice(titles) {
  return function PreviewChoice({ children }) {
    const [selected, setSelected] = useState("0");
    const variants = React.Children.toArray(children).filter(
      (child) => typeof child !== "string" || child.trim().length > 0,
    );
    if (variants.length < 2 || variants.length > 8) {
      throw new Error(
        "PreviewChoice requires between two and eight module alternatives.",
      );
    }
    for (const variant of variants) {
      if (
        !React.isValidElement(variant) ||
        !titles.has(variant.type) ||
        Object.keys(variant.props).length > 0
      ) {
        throw new Error(
          "PreviewChoice alternatives must be imported MDX modules without props.",
        );
      }
    }
    return React.createElement(
      "section",
      { className: "preview-choice", "data-preview-choice": "" },
      React.createElement(
        "label",
        { className: "preview-choice__selector" },
        React.createElement("span", null, "Version"),
        React.createElement(
          "select",
          {
            value: selected,
            onChange: (event) => setSelected(event.target.value),
            "data-choice-select": "",
          },
          variants.map((variant, index) =>
            React.createElement(
              "option",
              { key: index, value: String(index) },
              titles.get(variant.type),
            ),
          ),
        ),
      ),
      variants.map((variant, index) =>
        React.createElement(
          "div",
          {
            key: index,
            className: "preview-choice__panel",
            "data-choice-panel": String(index),
            hidden: String(index) !== selected,
          },
          variant,
        ),
      ),
    );
  };
}
