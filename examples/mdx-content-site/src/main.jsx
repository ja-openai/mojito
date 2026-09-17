import { createRoot, hydrateRoot } from "react-dom/client";
import "../styles.css";
import { App, pageTitle } from "./App.jsx";

const locale = document.documentElement.lang;
const page = document.body.dataset.page;
const container = document.getElementById("root");
document.title = pageTitle(locale, page);
const app = <App locale={locale} page={page} />;
if (container.dataset.prerendered === "true") hydrateRoot(container, app);
else createRoot(container).render(app);
