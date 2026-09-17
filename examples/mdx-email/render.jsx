import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MDXProvider } from "@mdx-js/react";
import { convert } from "html-to-text";
import manifest from "./.generated/manifest.json";

const modules = import.meta.glob("./.generated/content/**/*.mdx", {
  eager: true,
  import: "default",
});
const components = {
  h2: (props) => (
    <h2
      {...props}
      style={{ fontSize: 28, lineHeight: 1.25, margin: "24px 0 16px" }}
    />
  ),
  h3: (props) => (
    <h3
      {...props}
      style={{ color: "#17665b", fontSize: 18, margin: "0 0 32px" }}
    />
  ),
  p: (props) => (
    <p
      {...props}
      style={{ fontSize: 16, lineHeight: 1.65, margin: "16px 0" }}
    />
  ),
  a: (props) => (
    <a {...props} style={{ color: "#17665b", textDecoration: "underline" }} />
  ),
};
export { manifest };
export function renderEmail(locale, email) {
  const fields = manifest.fields[locale]?.[email];
  if (!fields) throw new Error(`Unknown email: ${locale}/${email}`);
  const Body = modules[`./.generated/content/${locale}/${email}_body.mdx`];
  const content = (
    <MDXProvider components={components}>
      <Body />
    </MDXProvider>
  );
  const fragment = renderToStaticMarkup(content);
  const html =
    "<!doctype html>" +
    renderToStaticMarkup(
      <html lang={locale} dir={locale === "ar" ? "rtl" : "ltr"}>
        <head>
          <meta charSet="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <title>{fields.subject}</title>
        </head>
        <body
          style={{
            margin: 0,
            backgroundColor: "#f2f5f4",
            fontFamily: "Arial, sans-serif",
            color: "#243530",
          }}
        >
          <div
            style={{
              display: "none",
              maxHeight: 0,
              overflow: "hidden",
              msoHide: "all",
            }}
          >
            {fields.preheader}
          </div>
          <table
            role="presentation"
            width="100%"
            cellPadding="0"
            cellSpacing="0"
          >
            <tbody>
              <tr>
                <td align="center" style={{ padding: "28px 12px" }}>
                  <table
                    role="presentation"
                    width="600"
                    cellPadding="0"
                    cellSpacing="0"
                    style={{
                      width: "100%",
                      maxWidth: 600,
                      backgroundColor: "#ffffff",
                      borderTop: "4px solid #17665b",
                    }}
                  >
                    <tbody>
                      <tr>
                        <td style={{ padding: "32px" }}>{content}</td>
                      </tr>
                    </tbody>
                  </table>
                </td>
              </tr>
            </tbody>
          </table>
        </body>
      </html>,
    );
  return { ...fields, html, text: convert(fragment, { wordwrap: 78 }) };
}
