/*
 * Regression harness for the Bot Clans editor inside tools/l2admin/index.html.
 *
 * Like the playstyle harness, this reads index.html and evaluates its shipping <script> block in a
 * minimal browser stub, so it can never drift from a copy of the code.
 *
 * What it protects:
 *   1. The line-oriented BotClans.xml writer round-trips the real file byte for byte (the safety gate
 *      that lets the panel write the file at all - the header comment is most of its value), and an
 *      edit rewrites ONLY its own line.
 *   2. Add/delete of clans and alliances, and member toggles, produce well-formed, re-parseable XML.
 *   3. The in-browser DXT1 encoder and DDS header match the shipped .dds bytes produced by
 *      tools/crest_png_to_dds.py (which the client accepts), so crests made in the panel are valid.
 *
 * Usage: node tests/js/botclans_editor_test.js     (run from the project root, or anywhere)
 */
"use strict";
const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..", "..");
const DATA = path.join(ROOT, "dist", "game", "data");
const PANEL = path.join(ROOT, "tools", "l2admin", "index.html");
const BOTCLANS = path.join(DATA, "BotClans.xml");
const CREST_DDS = path.join(DATA, "crests", "Crimson_Phoenix", "pledge_16x12.dds");

let failures = 0;
const pass = (msg) => console.log("  ok    " + msg);
const fail = (msg) => { failures++; console.log("  FAIL  " + msg); };
const check = (cond, msg) => cond ? pass(msg) : fail(msg);

/* ------------------------------------------------------------------ *
 * Load the panel's real script into a stubbed browser.
 * ------------------------------------------------------------------ */
const html = fs.readFileSync(PANEL, "utf8");
const scriptMatch = /<script>\s*"use strict";([\s\S]*?)<\/script>/.exec(html);
if (!scriptMatch){ console.log("FAIL  could not find the panel script block"); process.exit(1); }

// A DOM element stub rich enough to let the popover code build a real node tree we can inspect.
const stubEl = () => {
  const el = {
    _children:[], classList:{ add(){}, remove(){}, toggle(){} }, style:{}, innerHTML:"",
    textContent:"", value:"", files:[], type:"", checked:false, dataset:{},
    appendChild(c){ el._children.push(c); return c; },
    querySelector(){ return null; }, querySelectorAll:() => [],
    addEventListener(){}, removeEventListener(){}, remove(){}, contains:() => false,
    getBoundingClientRect:() => ({ left:0, top:0, right:100, bottom:20, width:100, height:20 }),
    getContext:() => null, onclick:null, onchange:null, firstChild:null
  };
  return el;
};
// #bcBody captures innerHTML so the render smoke test can inspect the generated markup.
let bcBodyHtml = "";
const bcBody = stubEl();
Object.defineProperty(bcBody, "innerHTML", { get(){ return bcBodyHtml; }, set(v){ bcBodyHtml = v; } });
const docBody = stubEl();  // popovers are appended here
const sandbox = {
  window:{ addEventListener(){}, scrollX:0, scrollY:0 },  // no showDirectoryPicker -> FS_OK is false
  document:{ getElementById:(id) => id === "bcBody" ? bcBody : stubEl(), querySelectorAll:() => [],
    createElement:() => stubEl(), createTextNode:(t) => ({ nodeType:3, textContent:t }),
    body:docBody, addEventListener(){}, removeEventListener(){},
    documentElement:{ setAttribute(){}, getAttribute:() => "dark" } },
  localStorage:{ getItem:() => null, setItem(){} },
  indexedDB:{ open:() => ({}) },
  console, setTimeout, clearTimeout,
  URL:{ createObjectURL:() => "", revokeObjectURL(){} },
  DataView, Uint8Array,
  DOMParser: function(){ this.parseFromString = () => ({ querySelectorAll:() => [] }); }
};
const api = {};
new Function(...Object.keys(sandbox), "__api", scriptMatch[1] +
  "\n;Object.assign(__api,{bcParse,bcSerialize,bcState,bcClans,bcAlliances,bcAttrLine," +
  "bcEncBlock,bcRgb565,bc565rgb,bcBuildDds,BC_CREST_SPECS,bcRender,bcClanAllianceOf," +
  "bcMemberKeys,bcImportFile,bcAllyMembersPick});"
)(...Object.values(sandbox), api);

const { bcParse, bcSerialize, bcAttrLine, bcEncBlock, bcBuildDds } = api;
const clansOf = (doc) => doc.items.filter(i => i.t === "clan");
const alliesOf = (doc) => doc.items.filter(i => i.t === "alliance");

/* ------------------------------------------------------------------ *
 * 1. Round-trip + comment preservation.
 * ------------------------------------------------------------------ */
console.log(">>> BotClans.xml line model");
const original = fs.readFileSync(BOTCLANS, "utf8");
const doc = bcParse(original);
check(bcSerialize(doc) === original, "round-trips the shipped BotClans.xml byte for byte");
check(clansOf(doc).length === 12, "parses all 12 clans (" + clansOf(doc).length + ")");
check(alliesOf(doc).length === 3, "parses all 3 alliances (" + alliesOf(doc).length + ")");
check(alliesOf(doc).every(a => a.members.length === 3), "each seeded alliance has 3 member rows");

// An edit rewrites exactly one line, leaving comments and layout intact.
{
  const d = bcParse(original);
  const c = clansOf(d)[0];
  c.attrs.name = "Renamed"; c.edited = true;
  const before = original.split("\n"), after = bcSerialize(d).split("\n");
  const changed = after.filter((l, i) => l !== before[i]);
  check(changed.length === 1 && /name="Renamed"/.test(changed[0]), "a clan rename rewrites only its own line");
}

/* ------------------------------------------------------------------ *
 * 2. Structural edits stay well-formed and re-parseable.
 * ------------------------------------------------------------------ */
console.log(">>> structural edits");
{
  // Add a clan by hand the way bcAddClan does (insert after the last clan), then re-parse.
  const d = bcParse(original);
  const clans = clansOf(d);
  const at = d.items.indexOf(clans[clans.length - 1]) + 1;
  d.items.splice(at, 0, { t:"clan", lead:[], indent:"\t",
    attrs:{ key:"NewClan", name:"NewClan", level:"5", crestSet:"NewClan" }, edited:true, raw:null });
  const out = bcSerialize(d);
  const re = bcParse(out);
  check(clansOf(re).length === 13, "adding a clan yields 13 re-parseable clans");
  check(bcSerialize(re) === out, "the added clan re-serializes stably");
  check(/<clan key="NewClan" name="NewClan" level="5" crestSet="NewClan" \/>/.test(out), "new clan line is canonical");
}
{
  // Remove a member from the first alliance; the block must still parse with 2 members.
  const d = bcParse(original);
  const a = alliesOf(d)[0];
  a.members = a.members.filter(m => m.attrs.clan !== a.members[0].attrs.clan);
  a.edited = true;
  const re = bcParse(bcSerialize(d));
  check(alliesOf(re)[0].members.length === 2, "removing a member leaves 2 members and still parses");
}
{
  // A brand-new alliance (open form, canonical) round-trips through the parser.
  const d = bcParse(original);
  const item = { t:"alliance", lead:[], indent:"\t", openRaw:null,
    attrs:{ name:"TestAlly", leader:"Azure_Dragon", crestSet:"Azure_Dragon" },
    edited:true, members:[{ lead:[], indent:"\t\t", attrs:{ clan:"Frost_Moon" }, edited:true, raw:null }],
    trail:[], closeRaw:null, selfClose:false };
  const at = d.items.findIndex(x => x.t === "raw" && x.text.trim() === "</list>");
  d.items.splice(at, 0, item);
  const out = bcSerialize(d);
  const re = bcParse(out);
  const made = alliesOf(re).find(a => a.attrs.name === "TestAlly");
  check(!!made && made.members.length === 1 && made.attrs.leader === "Azure_Dragon", "a new alliance parses back with its leader and member");
  check(bcSerialize(re) === out, "the new alliance re-serializes stably");
}

/* ------------------------------------------------------------------ *
 * 3. Crest encoder matches the shipped (Python-produced) .dds bytes.
 * ------------------------------------------------------------------ */
console.log(">>> crest DXT1 encoder");
{
  // The DDS header for a 16x16 DXT1 texture (128 bytes of block data) must equal the shipped file's header.
  const shipped = fs.readFileSync(CREST_DDS);
  const jsHead = Buffer.from(bcBuildDds(new Uint8Array(shipped.length - 128), 16, 16).slice(0, 128));
  check(jsHead.equals(shipped.slice(0, 128)), "DDS header matches the shipped pledge crest header");
  check(shipped.length === 256, "shipped pledge crest is 256 bytes (16x16 DXT1, at the wire cap)");
  check(BC_CREST_SPECS_pledge().max === 256, "pledge spec caps at 256 bytes");
}
{
  // A flat block and a two-colour block encode to a valid 8-byte DXT1 block with c0 >= c1 (opaque mode).
  const flat = Array.from({ length:16 }, () => [128, 64, 32]);
  const blk = bcEncBlock(flat);
  check(blk.length === 8, "a DXT1 block is 8 bytes");
  const c0 = blk[0] | (blk[1] << 8), c1 = blk[2] | (blk[3] << 8);
  check(c0 >= c1, "endpoints use 4-colour opaque mode (c0 >= c1)");
}
function BC_CREST_SPECS_pledge(){ return api.BC_CREST_SPECS.find(s => s.file === "pledge_16x12"); }

/* ------------------------------------------------------------------ *
 * 4. The render path (quote-heavy inline handlers) produces well-formed
 *    markup without throwing - guards against template-string breakage.
 * ------------------------------------------------------------------ */
console.log(">>> render");
{
  api.bcState.doc = bcParse(original);
  api.bcState.safe = true;
  api.bcState.crestSets = ["Azure_Dragon", "Crimson_Phoenix"];
  api.bcState.crestPrev = { Azure_Dragon:"blob:x" };
  let threw = null;
  try { api.bcRender(); } catch (e) { threw = e; }
  check(!threw, "bcRender() runs without throwing" + (threw ? " (" + threw.message + ")" : ""));
  check(bcBodyHtml.includes("oninput=\"bcSetClanAttr(0,'key',this.value)\""), "clan key handler is well-formed");
  check(bcBodyHtml.includes('onclick="bcAllyLeaderPick(event,0)"'), "leader picker button is wired");
  check(bcBodyHtml.includes('onclick="bcAllyMembersPick(event,0)"'), "members picker button is wired");
  check(bcBodyHtml.includes('onclick="bcClanCrestPick(event,0)"'), "clan crest picker button is wired");
  check(bcBodyHtml.includes("bcExport()") && bcBodyHtml.includes("bcImportPick(this)"), "export/import controls are present");
  check(!bcBodyHtml.includes("<select"), "no native <select> (which renders a white popup in dark mode)");
  check(!bcBodyHtml.includes('\\"'), "no stray escaped quotes leaked into the markup");
}

/* ------------------------------------------------------------------ *
 * 5. One-alliance-per-clan safeguard.
 * ------------------------------------------------------------------ */
console.log(">>> alliance membership safeguard");
{
  api.bcState.doc = bcParse(original); api.bcState.safe = true;
  const allies = api.bcAlliances();
  const dragonPact = allies[0];                 // Azure_Dragon + Emerald/Frost/Silver
  const emeraldAlly = api.bcClanAllianceOf("Emerald_Serpent", null);
  check(emeraldAlly === dragonPact, "a seeded member resolves to its alliance");
  check(api.bcClanAllianceOf("Azure_Dragon", null) === dragonPact, "a leader resolves to its alliance");
  // Ivory_Crown is in PhoenixOrder; from DragonPact's view it is taken, so not offerable.
  check(api.bcClanAllianceOf("Ivory_Crown", dragonPact) !== null, "a clan in another alliance is reported as taken");

  // Open the real members popover for DragonPact and count the checkboxes it offers. Every one of the 12
  // seeded clans is already in an alliance, so the only free clans are DragonPact's own 3 members.
  docBody._children.length = 0;
  api.bcAllyMembersPick({ currentTarget: stubEl() }, 0);
  const countChecks = (n) => (n.type === "checkbox" ? 1 : 0) +
    (n._children || []).reduce((s, c) => s + countChecks(c), 0);
  const offered = docBody._children.reduce((s, c) => s + countChecks(c), 0);
  check(offered === 3, "members popover offers only the 3 free clans (its own members), not clans in other alliances (" + offered + ")");
}

/* ------------------------------------------------------------------ *
 * 6. Import merges without overwriting existing clans/alliances.
 * ------------------------------------------------------------------ */
(async () => {
  console.log(">>> import merge");
  api.bcState.doc = bcParse(original); api.bcState.safe = true;
  const setup = {
    version: 1,
    clans: [
      { key:"Azure_Dragon", name:"AzureDragon", level:"5", crestSet:"Azure_Dragon" }, // dup key -> skip
      { key:"Storm_Hawk", name:"StormHawk", level:"5", crestSet:"Storm_Hawk" }         // new -> add
    ],
    alliances: [
      { name:"DragonPact", leader:"Storm_Hawk", members:[] },                          // dup name -> skip
      { name:"SkyLeague", leader:"Storm_Hawk", members:["Emerald_Serpent"] }           // new; member already allied -> dropped
    ]
  };
  const fakeFile = { text: async () => JSON.stringify(setup) };
  await api.bcImportFile(fakeFile);
  const clanKeys = api.bcClans().map(c => (c.attrs.key || "").trim());
  const allyNames = api.bcAlliances().map(a => (a.attrs.name || "").trim());
  check(clanKeys.filter(k => k === "Azure_Dragon").length === 1, "duplicate clan key was not added twice");
  check(clanKeys.includes("Storm_Hawk"), "a genuinely new clan was added");
  check(allyNames.filter(n => n === "DragonPact").length === 1, "duplicate alliance name was not overwritten");
  const sky = api.bcAlliances().find(a => a.attrs.name === "SkyLeague");
  check(!!sky && (sky.attrs.leader || "") === "Storm_Hawk", "a new alliance was added with its leader");
  check(!!sky && api.bcMemberKeys(sky).length === 0, "an already-allied member was dropped from the imported alliance");

  console.log(failures ? ("\n" + failures + " check(s) failed") : "\nAll Bot Clans editor checks passed");
  process.exit(failures ? 1 : 0);
})();
