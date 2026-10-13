// Synthetic product copy: deterministic, multilingual, and deliberately varied.
// Repeated templates still compress better than many real product catalogs.
export const locales = ["en", "fr", "ar"];
export const resourceNames = ["account", "cart", "orders", "settings", "help", "billing", "search", "alerts", "profile", "library"];

const words = {
  en: {
    topics: ["Account", "Cart", "Orders", "Settings", "Help", "Billing", "Search", "Alerts", "Profile", "Library"],
    sections: ["Overview", "Details", "Preferences", "Activity", "Access", "History", "Delivery", "Support", "Updates", "Summary"],
    patterns: [
      ["Your changes have been saved.", "Your changes have been saved."],
      ["Welcome, {$name}.", "Welcome, {name}."],
      ["Total: {$amount :number maximumFractionDigits=2}", "Total: {amount, number, ::.##}"],
      ["Price: {$amount :currency currency=USD}", "Price: {amount, number, ::currency/USD}"],
      ["Due: {$date :date length=long}", "Due: {date, date, long}"],
      ["Read {#link}the {#strong}terms{/strong}{/link}, {$name}.", "Read <link>the <strong>terms</strong></link>, {name}."],
    ],
    one: "You have {$count} item.", other: "You have {$count} items.",
    active: "Account active: {$name}", paused: "Account paused: {$name}",
  },
  fr: {
    topics: ["Compte", "Panier", "Commandes", "Paramètres", "Aide", "Facturation", "Recherche", "Alertes", "Profil", "Bibliothèque"],
    sections: ["Aperçu", "Détails", "Préférences", "Activité", "Accès", "Historique", "Livraison", "Assistance", "Actualités", "Résumé"],
    patterns: [
      ["Vos modifications sont enregistrées.", "Vos modifications sont enregistrées."],
      ["Bienvenue, {$name}.", "Bienvenue, {name}."],
      ["Total : {$amount :number maximumFractionDigits=2}", "Total : {amount, number, ::.##}"],
      ["Prix : {$amount :currency currency=USD}", "Prix : {amount, number, ::currency/USD}"],
      ["Échéance : {$date :date length=long}", "Échéance : {date, date, long}"],
      ["Lisez {#link}les {#strong}conditions{/strong}{/link}, {$name}.", "Lisez <link>les <strong>conditions</strong></link>, {name}."],
    ],
    one: "Vous avez {$count} article.", other: "Vous avez {$count} articles.",
    active: "Compte actif : {$name}", paused: "Compte suspendu : {$name}",
  },
  ar: {
    topics: ["الحساب", "السلة", "الطلبات", "الإعدادات", "المساعدة", "الفوترة", "البحث", "التنبيهات", "الملف", "المكتبة"],
    sections: ["نظرة عامة", "التفاصيل", "التفضيلات", "النشاط", "الوصول", "السجل", "التوصيل", "الدعم", "التحديثات", "الملخص"],
    patterns: [
      ["تم حفظ التغييرات.", "تم حفظ التغييرات."],
      ["مرحبًا، {$name}.", "مرحبًا، {name}."],
      ["المجموع: {$amount :number maximumFractionDigits=2}", "المجموع: {amount, number, ::.##}"],
      ["السعر: {$amount :currency currency=USD}", "السعر: {amount, number, ::currency/USD}"],
      ["الموعد: {$date :date length=long}", "الموعد: {date, date, long}"],
      ["اقرأ {#link}{#strong}الشروط{/strong}{/link}، {$name}.", "اقرأ <link><strong>الشروط</strong></link>، {name}."],
    ],
    one: "لديك {$count} عنصر.", other: "عدد العناصر: {$count}.",
    active: "الحساب نشط: {$name}", paused: "الحساب متوقف: {$name}",
  },
};

export function makeCatalog(locale, count) {
  const copy = words[locale];
  if (!copy) throw new Error(`Unknown fixture locale: ${locale}`);
  const mf2 = {}, formatjs = {};
  for (let index = 0; index < count; index++) {
    const resourceIndex = index % resourceNames.length;
    const section = Math.floor(index / resourceNames.length) % copy.sections.length;
    const revision = Math.floor(index / (resourceNames.length * copy.sections.length));
    const prefix = `${copy.topics[resourceIndex]} / ${copy.sections[section]} ${revision + 1}: `;
    const id = `${resourceNames[resourceIndex]}.message.${String(index).padStart(4, "0")}`;
    const kind = Math.floor(index / resourceNames.length) % 8;
    if (kind < copy.patterns.length) {
      mf2[id] = prefix + copy.patterns[kind][0];
      formatjs[id] = prefix + copy.patterns[kind][1];
    } else if (kind === 6) {
      mf2[id] = `.input {$count :number}\n.match $count\none {{${prefix}${copy.one}}}\n* {{${prefix}${copy.other}}}`;
      const pound = text => text.replaceAll("{$count}", "#");
      formatjs[id] = `{count, plural, one {${prefix}${pound(copy.one)}} other {${prefix}${pound(copy.other)}}}`;
    } else {
      mf2[id] = `.input {$status :string}\n.match $status\nactive {{${prefix}${copy.active}}}\n* {{${prefix}${copy.paused}}}`;
      const argument = text => text.replaceAll("{$name}", "{name}");
      formatjs[id] = `{status, select, active {${prefix}${argument(copy.active)}} other {${prefix}${argument(copy.paused)}}}`;
    }
  }
  return { mf2, formatjs };
}

export function splitResources(messages) {
  const resources = Object.fromEntries(resourceNames.map(name => [name, {}]));
  for (const [name, message] of Object.entries(messages)) resources[name.split(".")[0]][name] = message;
  return resources;
}
