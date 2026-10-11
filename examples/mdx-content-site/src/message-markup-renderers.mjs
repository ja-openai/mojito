import React from "react";

function containsLink(children) {
  return React.Children.toArray(children).some(
    (child) =>
      React.isValidElement(child) &&
      (child.type === "a" || containsLink(child.props.children)),
  );
}

// Only application code owns elements, destinations and event handlers.
export const messageMarkupRenderers = {
  strong: (children) => React.createElement("strong", null, children),
  em: (children) => React.createElement("em", null, children),
  termsLink: (children) => {
    if (containsLink(children))
      throw new Error("termsLink cannot contain another link");
    return React.createElement(
      "a",
      { href: "https://example.com/terms" },
      children,
    );
  },
  br: (children) => {
    if (children !== null) throw new Error("br must be standalone MF2 markup");
    return React.createElement("br");
  },
};
