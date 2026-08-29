/**
 * Crown test PoC: select exactly 10 candidate tiles on a LOCAL fixture grid.
 * - Never opens photos.google.com
 * - Never clicks Delete
 * - Never uses a real Google account
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { chromium } from "playwright";

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, "..");
const FIXTURE_HTML = join(ROOT, "fixtures", "photos-grid.html");
const CANDIDATES_JSON = join(ROOT, "fixtures", "candidates.json");

function loadCandidates() {
  const raw = JSON.parse(readFileSync(CANDIDATES_JSON, "utf8"));
  const list = raw.candidates;
  if (!Array.isArray(list) || list.length !== 10) {
    throw new Error(`candidates.json must have exactly 10 candidates, got ${list?.length}`);
  }
  const ids = list.map((c) => c.id);
  if (new Set(ids).size !== 10) {
    throw new Error("candidate ids must be unique");
  }
  return ids;
}

function fail(msg) {
  console.error("FAIL:", msg);
  process.exitCode = 1;
}

async function main() {
  const candidateIds = loadCandidates();
  const candidateSet = new Set(candidateIds);
  const fileUrl = pathToFileURL(FIXTURE_HTML).href;

  console.log("Fixture:", fileUrl);
  console.log("Candidates (10):", candidateIds.join(", "));

  const browser = await chromium.launch({ headless: false });
  const page = await browser.newPage();

  try {
    await page.goto(fileUrl, { waitUntil: "domcontentloaded" });

    // Select ONLY the 10 candidates by stable data-media-key
    for (const id of candidateIds) {
      const tile = page.locator(`[data-media-key="${id}"]`);
      const count = await tile.count();
      if (count !== 1) {
        fail(`expected exactly 1 tile for ${id}, found ${count}`);
        return;
      }
      await tile.click();
    }

    // Never touch Delete
    const deleteBtn = page.locator("#btn-delete, [data-testid='delete-button']");
    // Soft check that Delete exists but we did not click it
    if ((await deleteBtn.count()) === 0) {
      fail("delete button missing from fixture (expected present but unused)");
      return;
    }

    const selectedTiles = page.locator(".tile.selected");
    const selectedCount = await selectedTiles.count();
    const countLabel = await page.locator("#selection-count").innerText();

    if (selectedCount !== 10) {
      fail(`selected count DOM=${selectedCount}, expected 10; UI="${countLabel}"`);
      return;
    }

    if (!/선택됨\s*10개/.test(countLabel)) {
      fail(`UI count label mismatch: "${countLabel}" (expected 선택됨 10개)`);
      return;
    }

    const selectedKeys = await selectedTiles.evaluateAll((els) =>
      els.map((el) => el.getAttribute("data-media-key"))
    );

    const missing = candidateIds.filter((id) => !selectedKeys.includes(id));
    const decoys = selectedKeys.filter((id) => !candidateSet.has(id));

    if (missing.length) {
      fail(`missing candidates: ${missing.join(", ")}`);
      return;
    }
    if (decoys.length) {
      fail(`decoy(s) selected: ${decoys.join(", ")}`);
      return;
    }

    console.log("UI:", countLabel);
    console.log("Selected keys:", selectedKeys.join(", "));
    console.log("Delete button: present, NOT clicked");
    console.log("PASS: selected exactly the 10 candidates; no decoys.");
  } catch (err) {
    fail(err?.stack || String(err));
  } finally {
    // Brief pause so headed demo is visible, then close
    await page.waitForTimeout(1200);
    await browser.close();
  }
}

main();
