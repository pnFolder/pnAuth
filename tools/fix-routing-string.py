from pathlib import Path

p = Path('shared/src/main/kotlin/ru/privatenull/pnauth/config/AuthConfig.kt')
text = p.read_text()
old = '''                throw IOException(
                    "$path must contain at least one server. Example:
" +
                        "$path:
  - server: auth
    online: 100
    type: SERVER"
                )'''
new = '''                throw IOException(
                    "$path must contain at least one server. Example:\\n" +
                        "$path:\\n  - server: auth\\n    online: 100\\n    type: SERVER"
                )'''
if old not in text:
    raise SystemExit('Broken validation string not found')
p.write_text(text.replace(old, new, 1))
