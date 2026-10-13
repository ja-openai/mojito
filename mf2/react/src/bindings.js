// Typed resource facades contain no message data and never import the parser.
import React, { useMemo } from "react";
import { MF2 as Message, useMF2 as useFormatter } from "./index.js";

export function createMessages(resource) {
  const bind = (formatter) => ({
    format: (name, args) => formatter.format(resource, name, args),
    render: (name, args, markupRenderers) =>
      formatter.render(resource, name, args, markupRenderers),
  });
  return {
    MF2: (props) => React.createElement(Message, { ...props, resource }),
    useMF2: () => {
      const formatter = useFormatter();
      return useMemo(() => bind(formatter), [formatter]);
    },
    bind,
  };
}
