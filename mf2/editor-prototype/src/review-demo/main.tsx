import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { ReviewDemoApp } from "./ReviewDemoApp";

const root = document.querySelector("#reviewDemoRoot");

if (!root) {
  throw new Error("Missing #reviewDemoRoot");
}

createRoot(root).render(
  <StrictMode>
    <ReviewDemoApp />
  </StrictMode>,
);
