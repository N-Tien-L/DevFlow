#!/usr/bin/env node

const https = require('https');
const fs = require('fs');
const path = require('path');

// 1. Resolve Credentials
function loadCredentials() {
  let apiKey = process.env.TRELLO_API_KEY;
  let token = process.env.TRELLO_TOKEN;
  let defaultBoardId = process.env.TRELLO_DEFAULT_BOARD_ID;

  // Try loading from .credentials.json in skill dir
  const skillCredPath = path.resolve(__dirname, '..', '.credentials.json');
  if (fs.existsSync(skillCredPath)) {
    try {
      const creds = JSON.parse(fs.readFileSync(skillCredPath, 'utf8'));
      apiKey = apiKey || creds.apiKey;
      token = token || creds.token;
      defaultBoardId = defaultBoardId || creds.defaultBoardId;
    } catch (e) {}
  }

  // Try loading from .env.trello at project root or CWD
  const searchDirs = [process.cwd(), path.resolve(__dirname, '../../..')];
  for (const dir of searchDirs) {
    const envPath = path.join(dir, '.env.trello');
    if (fs.existsSync(envPath)) {
      try {
        const lines = fs.readFileSync(envPath, 'utf8').split('\n');
        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed || trimmed.startsWith('#')) continue;
          const [k, ...v] = trimmed.split('=');
          const key = k.trim();
          const val = v.join('=').trim();
          if (key === 'TRELLO_API_KEY') apiKey = apiKey || val;
          if (key === 'TRELLO_TOKEN') token = token || val;
          if (key === 'TRELLO_DEFAULT_BOARD_ID') defaultBoardId = defaultBoardId || val;
        }
      } catch (e) {}
    }
  }

  if (!apiKey || !token) {
    console.error('❌ Error: Trello API Key or Token not found.');
    console.error('Please ensure TRELLO_API_KEY and TRELLO_TOKEN are set in environment or in .env.trello / .credentials.json');
    process.exit(1);
  }

  return { apiKey, token, defaultBoardId: defaultBoardId || '6aa2a6d90ff05c484f131687' };
}

const { apiKey, token, defaultBoardId } = loadCredentials();

// 2. HTTP Request Helper
function apiRequest(method, endpoint, body = null) {
  return new Promise((resolve, reject) => {
    const url = new URL(`https://api.trello.com/1${endpoint}`);
    url.searchParams.set('key', apiKey);
    url.searchParams.set('token', token);

    const options = {
      method,
      headers: {
        'Accept': 'application/json',
      }
    };

    let postData = '';
    if (body) {
      options.headers['Content-Type'] = 'application/json';
      postData = JSON.stringify(body);
      options.headers['Content-Length'] = Buffer.byteLength(postData);
    }

    const req = https.request(url, options, (res) => {
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => {
        try {
          if (res.statusCode >= 200 && res.statusCode < 300) {
            resolve(JSON.parse(data));
          } else {
            reject(new Error(`HTTP ${res.statusCode}: ${data}`));
          }
        } catch (e) {
          resolve(data);
        }
      });
    });

    req.on('error', reject);
    if (body) req.write(postData);
    req.end();
  });
}

// 3. CLI Argument Parser
function parseArgs(args) {
  const result = { _: [] };
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg.startsWith('--')) {
      const key = arg.slice(2);
      const next = args[i + 1];
      if (next && !next.startsWith('--')) {
        result[key] = next;
        i++;
      } else {
        result[key] = true;
      }
    } else {
      result._.push(arg);
    }
  }
  return result;
}

// 4. Command Handlers
async function main() {
  const argv = process.argv.slice(2);
  const parsed = parseArgs(argv);
  const command = parsed._[0];

  if (!command || command === 'help') {
    printHelp();
    return;
  }

  try {
    switch (command) {
      case 'list-boards': {
        const boards = await apiRequest('GET', '/members/me/boards?fields=name,id,url,idOrganization');
        console.log('\n📋 Accessible Trello Boards:');
        boards.forEach(b => {
          const isDef = b.id === defaultBoardId ? ' [DEFAULT]' : '';
          console.log(`- ${b.name}${isDef} (ID: ${b.id}) -> ${b.url}`);
        });
        break;
      }

      case 'list-lists': {
        const boardId = parsed._[1] || defaultBoardId;
        const lists = await apiRequest('GET', `/boards/${boardId}/lists?fields=name,id,pos,closed`);
        console.log(`\n📑 Columns/Lists for Board [${boardId}]:`);
        lists.filter(l => !l.closed).forEach(l => {
          console.log(`- [${l.name}] (ID: ${l.id})`);
        });
        break;
      }

      case 'create-list': {
        const name = parsed._[1] || parsed.name;
        const boardId = parsed._[2] || parsed.boardId || defaultBoardId;
        if (!name) {
          console.error('Usage: trello create-list <name> [boardId]');
          process.exit(1);
        }
        const list = await apiRequest('POST', '/lists', {
          name,
          idBoard: boardId,
          pos: parsed.pos || 'bottom'
        });
        console.log(`✅ List created: "${list.name}" (ID: ${list.id})`);
        break;
      }

      case 'list-cards': {
        const listId = parsed._[1];
        if (!listId) {
          console.error('Usage: trello list-cards <listId>');
          process.exit(1);
        }
        const cards = await apiRequest('GET', `/lists/${listId}/cards?fields=name,id,url,pos,labels`);
        console.log(`\n🗂️ Cards in list [${listId}]: (${cards.length} cards)`);
        cards.forEach((c, idx) => {
          const labels = c.labels.map(l => `[${l.name || l.color}]`).join(' ');
          console.log(`${idx + 1}. ${c.name} ${labels} (ID: ${c.id})`);
          console.log(`   URL: ${c.url}`);
        });
        break;
      }

      case 'get-card': {
        const cardId = parsed._[1];
        if (!cardId) {
          console.error('Usage: trello get-card <cardId>');
          process.exit(1);
        }
        const card = await apiRequest('GET', `/cards/${cardId}?checklists=all&actions=commentCard&fields=name,id,desc,url,idList,labels,due,closed`);
        console.log(`\n📌 Card: ${card.name} (ID: ${card.id})`);
        console.log(`URL: ${card.url}`);
        console.log(`List ID: ${card.idList}`);
        if (card.labels && card.labels.length) {
          console.log(`Labels: ${card.labels.map(l => l.name || l.color).join(', ')}`);
        }
        console.log('\n--- Description ---');
        console.log(card.desc || '(No description)');
        console.log('-------------------');

        if (card.checklists && card.checklists.length) {
          console.log('\n📋 Checklists:');
          card.checklists.forEach(cl => {
            console.log(`\n[Checklist: ${cl.name}] (ID: ${cl.id})`);
            cl.checkItems.forEach(ci => {
              const state = ci.state === 'complete' ? '[x]' : '[ ]';
              console.log(`  ${state} ${ci.name} (Item ID: ${ci.id})`);
            });
          });
        }
        break;
      }

      case 'create-card': {
        const listId = parsed._[1] || parsed.listId;
        const name = parsed.name || parsed._[2];
        const desc = parsed.desc || '';
        const color = parsed.color;
        const pos = parsed.pos || 'bottom';

        if (!listId || !name) {
          console.error('Usage: trello create-card <listId> --name "Title" [--desc "Description"] [--color "sky|blue|..."]');
          process.exit(1);
        }

        const payload = {
          idList: listId,
          name,
          desc,
          pos
        };
        if (color) {
          payload.cover = { color, size: 'normal', brightness: 'light' };
        }

        const card = await apiRequest('POST', '/cards', payload);
        console.log(`✅ Card created: "${card.name}" (ID: ${card.id})`);
        console.log(`   URL: ${card.url}`);
        break;
      }

      case 'move-card': {
        const cardId = parsed._[1];
        const targetListId = parsed._[2] || parsed.listId;
        if (!cardId || !targetListId) {
          console.error('Usage: trello move-card <cardId> <targetListId>');
          process.exit(1);
        }
        const updated = await apiRequest('PUT', `/cards/${cardId}`, { idList: targetListId });
        console.log(`✅ Card moved: "${updated.name}" is now in list [${updated.idList}]`);
        break;
      }

      case 'update-card': {
        const cardId = parsed._[1];
        if (!cardId) {
          console.error('Usage: trello update-card <cardId> [--name "..."] [--desc "..."] [--listId "..."] [--closed]');
          process.exit(1);
        }
        const payload = {};
        if (parsed.name) payload.name = parsed.name;
        if (parsed.desc) payload.desc = parsed.desc;
        if (parsed.listId) payload.idList = parsed.listId;
        if (parsed.closed !== undefined) payload.closed = parsed.closed === true || parsed.closed === 'true';

        const updated = await apiRequest('PUT', `/cards/${cardId}`, payload);
        console.log(`✅ Card updated: "${updated.name}"`);
        break;
      }

      case 'add-checklist': {
        const cardId = parsed._[1];
        const name = parsed.name || parsed._[2] || 'Checklist';
        if (!cardId) {
          console.error('Usage: trello add-checklist <cardId> --name "Checklist Title"');
          process.exit(1);
        }
        const cl = await apiRequest('POST', `/cards/${cardId}/checklists`, { name });
        console.log(`✅ Checklist added: "${cl.name}" (ID: ${cl.id}) to card [${cardId}]`);
        break;
      }

      case 'add-checkitem': {
        const checklistId = parsed._[1];
        const name = parsed.name || parsed._[2];
        if (!checklistId || !name) {
          console.error('Usage: trello add-checkitem <checklistId> --name "Item title"');
          process.exit(1);
        }
        const item = await apiRequest('POST', `/checklists/${checklistId}/checkItems`, { name });
        console.log(`✅ Item added: "[ ] ${item.name}" (ID: ${item.id})`);
        break;
      }

      case 'check-item': {
        const cardId = parsed._[1];
        const checkItemId = parsed._[2];
        const state = parsed.state || 'complete'; // 'complete' or 'incomplete'
        if (!cardId || !checkItemId) {
          console.error('Usage: trello check-item <cardId> <checkItemId> [--state complete|incomplete]');
          process.exit(1);
        }
        const res = await apiRequest('PUT', `/cards/${cardId}/checkItem/${checkItemId}`, { state });
        console.log(`✅ CheckItem updated: ${res.state === 'complete' ? '[x]' : '[ ]'} "${res.name}"`);
        break;
      }

      case 'add-comment': {
        const cardId = parsed._[1];
        const text = parsed.text || parsed._[2];
        if (!cardId || !text) {
          console.error('Usage: trello add-comment <cardId> --text "Your comment here"');
          process.exit(1);
        }
        await apiRequest('POST', `/cards/${cardId}/actions/comments`, { text });
        console.log(`✅ Comment added to card [${cardId}]`);
        break;
      }

      case 'search': {
        const query = parsed._[1] || parsed.query;
        const boardId = parsed.boardId || defaultBoardId;
        if (!query) {
          console.error('Usage: trello search "keyword" [--boardId id]');
          process.exit(1);
        }
        const results = await apiRequest('GET', `/search?query=${encodeURIComponent(query)}&idBoards=${boardId}&modelTypes=cards&card_fields=name,id,url,idList`);
        console.log(`\n🔍 Search results for "${query}": (${results.cards.length} cards found)`);
        results.cards.forEach(c => {
          console.log(`- ${c.name} (ID: ${c.id}) in list [${c.idList}] -> ${c.url}`);
        });
        break;
      }

      default:
        console.error(`Unknown command: ${command}`);
        printHelp();
    }
  } catch (err) {
    console.error('❌ Trello API Error:', err.message);
    process.exit(1);
  }
}

function printHelp() {
  console.log(`
Trello Management CLI Tool
==========================
Usage: node .agents/skills/trello-management/scripts/trello.js <command> [options]

Commands:
  list-boards                            List all accessible boards
  list-lists [boardId]                   List all lists/columns on a board
  create-list <name> [boardId]           Create a new column/list
  list-cards <listId>                    List all cards in a list
  get-card <cardId>                      Get card details, description & checklists
  create-card <listId> --name "..."      Create a new card (supports --desc, --color, --pos)
  move-card <cardId> <targetListId>      Move card to another column
  update-card <cardId> [options]         Update card (--name, --desc, --listId, --closed)
  add-checklist <cardId> --name "..."    Add a checklist to a card
  add-checkitem <checklistId> --name ".." Add an item to a checklist
  check-item <cardId> <checkItemId>      Mark checkitem complete or incomplete (--state)
  add-comment <cardId> --text "..."      Add a comment to a card
  search <query> [--boardId "..."]       Search cards on the board
`);
}

main();
