// Made-up cards for the demo, "as if recorded" - never real data. Dates are counted from today, so the
// cards always read "today", "tomorrow" and so on. Fields as in core/model/Note.kt.
window.TAPEDO_SAMPLE = function (lang, today, now) {
  var H = 3600 * 1000;
  var pl = lang === "pl";
  function n(o) { return o; }
  function items(list, group) {
    return list.map(function (x) {
      return typeof x === "string" ? { text: x, checked: false, group: group || null } : { text: x[0], checked: !!x[1], group: x[2] || group || null };
    });
  }
  var shop = pl ? [
    ["pomidory", 0, "PRODUCE"], ["banany", 0, "PRODUCE"], ["cebula", 1, "PRODUCE"], ["chleb żytni", 0, "BAKERY"],
    ["mleko", 1, "DAIRY"], ["jogurt naturalny", 0, "DAIRY"], ["masło", 0, "DAIRY"], ["woda gazowana", 0, "DRINKS"],
    ["płyn do naczyń", 0, "HOUSEHOLD"]
  ] : [
    ["tomatoes", 0, "PRODUCE"], ["bananas", 0, "PRODUCE"], ["onions", 1, "PRODUCE"], ["rye bread", 0, "BAKERY"],
    ["milk", 1, "DAIRY"], ["plain yoghurt", 0, "DAIRY"], ["butter", 0, "DAIRY"], ["sparkling water", 0, "DRINKS"],
    ["washing-up liquid", 0, "HOUSEHOLD"]
  ];
  var grill = pl ? [
    ["kiełbaski", 0, "MEAT"], ["węgiel drzewny", 0, "HOUSEHOLD"], ["musztarda", 0, "PANTRY"], ["bułki", 0, "BAKERY"], ["lody", 0, "FROZEN"]
  ] : [
    ["sausages", 0, "MEAT"], ["charcoal", 0, "HOUSEHOLD"], ["mustard", 0, "PANTRY"], ["bread rolls", 0, "BAKERY"], ["ice cream", 0, "FROZEN"]
  ];
  var cards = [
    n({ type: "SHOPPING", title: pl ? "Zakupy" : "Shopping", priority: "MEDIUM", byAi: true, items: items(shop), ago: 2 }),
    n({ type: "SHOPPING", title: pl ? "Zakupy na grilla w sobotę" : "Shopping for Saturday's barbecue", priority: "LOW", items: items(grill), due: 3, ago: 30 }),
    n({ type: "CHECKLIST", title: pl ? "Spakować na wyjazd" : "Pack for the trip", priority: "HIGH", due: 5, ago: 20,
      items: items(pl ? ["ładowarka", ["paszport", 1], "krem z filtrem", "książka na pociąg", "słuchawki"] : ["charger", ["passport", 1], "sunscreen", "a book for the train", "headphones"]) }),
    n({ type: "CHECKLIST", title: pl ? "Przed przeprowadzką" : "Before the move", priority: "MEDIUM", ago: 70,
      items: items(pl ? [["zamówić kartony", 1], "wypowiedzieć internet", "spisać licznik prądu", "zmienić adres w banku"] : [["order boxes", 1], "cancel the internet", "read the electricity meter", "change the address at the bank"]) }),
    n({ type: "TASK", title: pl ? "Zadzwonić do dentysty" : "Call the dentist", body: pl ? "Umówić przegląd i zapytać o wybielanie." : "Book a check-up and ask about whitening.", priority: "HIGH", due: 1, ago: 1 }),
    n({ type: "TASK", title: pl ? "Opłacić rachunek za prąd" : "Pay the electricity bill", body: pl ? "Opłacić rachunek za prąd, termin mija dziś." : "Pay the electricity bill, it's due today.", priority: "HIGH", due: 0, ago: 26 }),
    n({ type: "TASK", title: pl ? "Wysłać CV do trzech firm" : "Send my CV to three companies", body: pl ? "Wysłać CV do trzech firm z listy i dopisać list motywacyjny." : "Send my CV to three companies on the list and add a cover letter.", priority: "MEDIUM", due: 2, byAi: true, ago: 5 }),
    n({ type: "TASK", title: pl ? "Oddać książki do biblioteki" : "Return the library books", priority: "LOW", due: 4, ago: 50 }),
    n({ type: "TASK", title: pl ? "Wymienić żarówkę w przedpokoju" : "Change the hall light bulb", priority: "LOW", done: true, ago: 80 }),
    n({ type: "EVENT", title: pl ? "Kawa z Anią" : "Coffee with Anna", body: pl ? "Kawa z Anią w czwartek o 18:30 w kawiarni na rynku." : "Coffee with Anna on Thursday at 6:30 pm at the café on the square.", priority: "MEDIUM", due: 2, time: 18 * 60 + 30, place: pl ? "kawiarnia na rynku" : "the café on the square", ago: 8 }),
    n({ type: "EVENT", title: pl ? "Weterynarz - szczepienie Burka" : "Vet - Rex's jab", body: pl ? "Szczepienie Burka, zabrać książeczkę zdrowia." : "Rex's vaccination, bring his health booklet.", priority: "HIGH", due: 6, time: 10 * 60 + 15, place: pl ? "lecznica przy Polnej" : "the clinic on Field Street", byAi: true, ago: 40 }),
    n({ type: "NOTE", title: pl ? "Rozmiar opon" : "Tyre size", body: pl ? "Opony zimowe 205/55 R16, ciśnienie 2,3 bara z przodu i z tyłu. Zmiana na zimowe przed pierwszym przymrozkiem." : "Winter tyres 205/55 R16, pressure 2.3 bar front and back. Switch to winter tyres before the first frost.", priority: "LOW", ago: 100 }),
    n({ type: "NOTE", title: pl ? "Naleśniki" : "Pancakes", body: pl ? "Szklanka mąki, szklanka mleka, pół szklanki wody, dwa jajka i szczypta soli. Odstawić na kwadrans." : "A cup of flour, a cup of milk, half a cup of water, two eggs and a pinch of salt. Let it rest for 15 minutes.", priority: "MEDIUM", ago: 120 }),
    n({ type: "IDEA", title: pl ? "Aplikacja do podlewania kwiatów" : "An app for watering plants", body: pl ? "Przypomina, kiedy podlać każdą roślinę, i uczy się, jak szybko schnie ziemia." : "Reminds you when to water each plant and learns how fast the soil dries.", priority: "MEDIUM", ago: 12 }),
    n({ type: "IDEA", title: pl ? "Bieszczady jesienią" : "The mountains in autumn", body: pl ? "Wyjazd w Bieszczady jesienią - sprawdzić noclegi w Wetlinie, może z rowerami." : "A trip to the mountains in autumn - check places to stay, maybe take the bikes.", priority: "MEDIUM", ago: 60 }),
    n({ type: "SHOPPING", title: pl ? "Zakupy" : "Shopping", priority: "LOW", ago: 90,
      items: items(pl ? [["kawa", 1, "PANTRY"], ["cukier", 1, "PANTRY"], ["papier do pieczenia", 1, "HOUSEHOLD"]] : [["coffee", 1, "PANTRY"], ["sugar", 1, "PANTRY"], ["baking paper", 1, "HOUSEHOLD"]]) })
  ];
  return cards.map(function (c, i) {
    return {
      id: i + 1, title: c.title, body: c.body || "", type: c.type, priority: c.priority || "MEDIUM",
      items: c.items || [], dueEpochDay: c.due == null ? null : today + c.due, timeMinutes: c.time == null ? null : c.time,
      place: c.place || null, done: !!c.done, byAi: !!c.byAi, createdAtMillis: now - c.ago * H
    };
  });
};
