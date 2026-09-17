import { renderToString } from "react-dom/server";
import { App } from "./App.jsx";

export function render({ locale, page }) {
  return renderToString(<App locale={locale} page={page} />);
}
