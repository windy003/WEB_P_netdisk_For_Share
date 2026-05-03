import os
import sqlite3
import secrets
from pathlib import Path
from functools import wraps
from datetime import timedelta, datetime
from flask import Flask, render_template, request, send_file, redirect, url_for, session, flash, abort, jsonify, g
from dotenv import load_dotenv

# 加载 .env 文件（override=True 确保 .env 配置优先于系统环境变量）
load_dotenv(override=True)

app = Flask(__name__)
app.secret_key = os.getenv('SECRET_KEY', secrets.token_hex(16))

# 禁用模板缓存，确保每次都加载最新模板
app.config['TEMPLATES_AUTO_RELOAD'] = True
app.jinja_env.auto_reload = True
app.config['SEND_FILE_MAX_AGE_DEFAULT'] = 0

# 设置会话持久化时间为30天
app.config['PERMANENT_SESSION_LIFETIME'] = timedelta(days=30)

# 从环境变量读取配置
CONFIG = {
    'SHARED_DIRECTORY': os.getenv('SHARED_DIRECTORY', r'D:\shared'),
    'ADMIN_USERNAME': os.getenv('ADMIN_USERNAME', os.getenv('USERNAME', 'admin')),
    'ADMIN_PASSWORD': os.getenv('ADMIN_PASSWORD', os.getenv('PASSWORD', 'admin123')),
    'PORT': int(os.getenv('PORT', 5003)),
    'HOST': os.getenv('HOST', '0.0.0.0'),
    'DEBUG': os.getenv('DEBUG', 'True').lower() == 'true',
}

# 数据库路径
DB_PATH = Path(__file__).parent / 'users.db'


def get_db():
    """获取数据库连接"""
    if 'db' not in g:
        g.db = sqlite3.connect(str(DB_PATH))
        g.db.row_factory = sqlite3.Row
    return g.db


@app.teardown_appcontext
def close_db(exception):
    db = g.pop('db', None)
    if db is not None:
        db.close()


def init_db():
    """初始化数据库，创建用户表并确保管理员账号存在"""
    db = sqlite3.connect(str(DB_PATH))
    db.execute('''CREATE TABLE IF NOT EXISTS users (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        username TEXT UNIQUE NOT NULL,
        password TEXT NOT NULL,
        is_admin INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL
    )''')

    # 确保管理员账号存在
    admin = db.execute('SELECT id FROM users WHERE username = ?',
                       (CONFIG['ADMIN_USERNAME'],)).fetchone()
    if not admin:
        db.execute('INSERT INTO users (username, password, is_admin, created_at) VALUES (?, ?, 1, ?)',
                   (CONFIG['ADMIN_USERNAME'],
                    CONFIG['ADMIN_PASSWORD'],
                    datetime.now().isoformat()))
        print(f"已创建管理员账号: {CONFIG['ADMIN_USERNAME']}")
    db.commit()
    db.close()

# Token 存储 (username -> token 映射)
# 在生产环境中应该使用数据库,这里为了简单使用内存字典
USER_TOKENS = {}


def generate_stream_token(username):
    """为用户生成流媒体访问 token"""
    token = secrets.token_urlsafe(32)  # 生成安全的随机 token
    USER_TOKENS[username] = {
        'token': token,
        'created_at': datetime.now()
    }
    return token


def verify_stream_token(token):
    """验证 token 是否有效"""
    if not token:
        return False

    # 检查 token 是否存在且未过期(7天有效期)
    for username, token_data in USER_TOKENS.items():
        if token_data['token'] == token:
            # 检查是否过期
            if datetime.now() - token_data['created_at'] < timedelta(days=7):
                return True
            else:
                # Token 过期,删除它
                del USER_TOKENS[username]
                return False

    return False


def login_required(f):
    """登录验证装饰器"""
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if not session.get('logged_in'):
            return redirect(url_for('login', next=request.url))
        return f(*args, **kwargs)
    return decorated_function


def admin_required(f):
    """管理员验证装饰器"""
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if not session.get('logged_in'):
            return redirect(url_for('login', next=request.url))
        if not session.get('is_admin'):
            abort(403)
        return f(*args, **kwargs)
    return decorated_function


def get_safe_path(relative_path):
    """获取安全的文件路径，防止路径遍历攻击"""
    base_path = Path(CONFIG['SHARED_DIRECTORY']).resolve()
    target_path = (base_path / relative_path).resolve()

    # 确保目标路径在共享目录内
    if not str(target_path).startswith(str(base_path)):
        abort(403)

    return target_path


def get_directory_contents(path):
    """获取目录内容"""
    items = []
    try:
        for item in sorted(path.iterdir(), key=lambda x: (not x.is_dir(), x.name.lower())):
            relative_path = item.relative_to(CONFIG['SHARED_DIRECTORY'])
            file_type = get_file_type(item.name) if item.is_file() else None
            items.append({
                'name': item.name,
                'is_dir': item.is_dir(),
                'size': item.stat().st_size if item.is_file() else 0,
                'path': str(relative_path).replace('\\', '/'),
                'file_type': file_type
            })
    except PermissionError:
        flash('没有权限访问此目录', 'error')
    return items


def format_size(size):
    """格式化文件大小"""
    for unit in ['B', 'KB', 'MB', 'GB', 'TB']:
        if size < 1024.0:
            return f"{size:.2f} {unit}"
        size /= 1024.0
    return f"{size:.2f} PB"


def get_file_type(filename):
    """根据文件扩展名判断文件类型"""
    ext = Path(filename).suffix.lower()

    # 音频文件
    audio_exts = ['.mp3', '.wav', '.ogg', '.m4a', '.aac', '.flac', '.wma']
    if ext in audio_exts:
        return 'audio'

    # 视频文件
    video_exts = ['.mp4', '.webm', '.ogg', '.avi', '.mov', '.mkv', '.flv']
    if ext in video_exts:
        return 'video'

    # 图片文件
    image_exts = ['.jpg', '.jpeg', '.png', '.gif', '.bmp', '.webp', '.svg']
    if ext in image_exts:
        return 'image'

    # PDF文件
    if ext == '.pdf':
        return 'pdf'

    return 'other'


app.jinja_env.filters['format_size'] = format_size


@app.route('/login', methods=['GET', 'POST'])
def login():
    """登录页面"""
    if request.method == 'POST':
        username = request.form.get('username', '').strip()
        password = request.form.get('password', '')

        db = get_db()
        user = db.execute('SELECT * FROM users WHERE username = ?', (username,)).fetchone()

        if user and user['password'] == password:
            session.permanent = True
            session['logged_in'] = True
            session['username'] = username
            session['is_admin'] = bool(user['is_admin'])

            generate_stream_token(username)

            next_page = request.args.get('next')
            return redirect(next_page or url_for('index'))
        else:
            flash('用户名或密码错误', 'error')

    return render_template('login.html')


@app.route('/logout')
def logout():
    """登出"""
    session.clear()
    return redirect(url_for('login'))


@app.route('/api/get_stream_token')
@login_required
def get_stream_token_api():
    """获取流媒体访问 token (API)"""
    username = session.get('username')

    # 获取或生成 token
    if username in USER_TOKENS:
        token_data = USER_TOKENS[username]
        # 检查是否过期
        if datetime.now() - token_data['created_at'] < timedelta(days=7):
            token = token_data['token']
        else:
            # 过期了,生成新的
            token = generate_stream_token(username)
    else:
        # 没有 token,生成新的
        token = generate_stream_token(username)

    return jsonify({
        'success': True,
        'token': token,
        'expires_in_days': 7
    })


@app.route('/')
@app.route('/browse/')
@app.route('/browse/<path:subpath>')
@login_required
def index(subpath=''):
    """浏览目录"""
    current_path = get_safe_path(subpath)

    if not current_path.exists():
        flash('路径不存在', 'error')
        return redirect(url_for('index'))

    if current_path.is_file():
        # 如果是文件，重定向到父目录
        return redirect(url_for('index'))

    # 获取目录内容
    items = get_directory_contents(current_path)

    # 构建面包屑导航
    breadcrumbs = []
    parts = Path(subpath).parts if subpath else []
    for i, part in enumerate(parts):
        breadcrumbs.append({
            'name': part,
            'path': '/'.join(parts[:i+1])
        })

    return render_template('index.html',
                         items=items,
                         current_path=subpath,
                         breadcrumbs=breadcrumbs)


@app.route('/play/<path:filepath>')
@login_required
def play(filepath):
    """播放音频/视频文件"""
    file_path = get_safe_path(filepath)

    if not file_path.exists() or not file_path.is_file():
        abort(404)

    file_type = get_file_type(file_path.name)

    if file_type not in ['audio', 'video']:
        flash('此文件类型不支持在线播放', 'error')
        return redirect(url_for('index'))

    # 获取同一目录下的所有媒体文件
    parent_dir = file_path.parent
    playlist = []
    current_index = 0

    try:
        for idx, item in enumerate(sorted(parent_dir.iterdir(), key=lambda x: x.name.lower())):
            if item.is_file():
                item_type = get_file_type(item.name)
                if item_type == file_type:  # 只添加相同类型的文件（音频或视频）
                    relative_path = item.relative_to(CONFIG['SHARED_DIRECTORY'])
                    playlist.append({
                        'name': item.name,
                        'path': str(relative_path).replace('\\', '/')
                    })
                    if item == file_path:
                        current_index = len(playlist) - 1
    except PermissionError:
        pass

    return render_template('player.html',
                         filename=file_path.name,
                         filepath=filepath,
                         file_type=file_type,
                         playlist=playlist,
                         current_index=current_index)


@app.route('/view/<path:filepath>')
@login_required
def view(filepath):
    """查看图片文件"""
    file_path = get_safe_path(filepath)

    if not file_path.exists() or not file_path.is_file():
        abort(404)

    file_type = get_file_type(file_path.name)

    if file_type != 'image':
        flash('此文件类型不支持查看', 'error')
        return redirect(url_for('index'))

    # 获取同一目录下的所有图片文件
    parent_dir = file_path.parent
    playlist = []
    current_index = 0

    try:
        for idx, item in enumerate(sorted(parent_dir.iterdir(), key=lambda x: x.name.lower())):
            if item.is_file():
                item_type = get_file_type(item.name)
                if item_type == 'image':  # 只添加图片文件
                    relative_path = item.relative_to(CONFIG['SHARED_DIRECTORY'])
                    playlist.append({
                        'name': item.name,
                        'path': str(relative_path).replace('\\', '/')
                    })
                    if item == file_path:
                        current_index = len(playlist) - 1
    except PermissionError:
        pass

    return render_template('viewer.html',
                         filename=file_path.name,
                         filepath=filepath,
                         playlist=playlist,
                         current_index=current_index)


@app.route('/stream/<path:filepath>')
def stream(filepath):
    """流式传输媒体文件 - 支持 Cookie 和 Token 认证"""
    # 首先检查 URL 参数中的 token
    token = request.args.get('token')

    # Token 认证
    if token and verify_stream_token(token):
        # Token 有效,允许访问
        pass
    # Cookie 认证(浏览器访问)
    elif session.get('logged_in'):
        # 已登录,允许访问
        pass
    else:
        # 两种认证都失败
        abort(403, 'Unauthorized: Invalid or missing token')

    file_path = get_safe_path(filepath)

    if not file_path.exists() or not file_path.is_file():
        abort(404)

    return send_file(file_path, as_attachment=False)


# ============ 用户管理（仅管理员） ============

@app.route('/admin/users')
@admin_required
def admin_users():
    """用户管理页面"""
    db = get_db()
    users = db.execute('SELECT id, username, password, is_admin, created_at FROM users ORDER BY id').fetchall()
    return render_template('admin_users.html', users=users)


@app.route('/admin/users/add', methods=['POST'])
@admin_required
def admin_add_user():
    """添加用户"""
    username = request.form.get('username', '').strip()
    password = request.form.get('password', '')

    if not username or not password:
        flash('用户名和密码不能为空', 'error')
        return redirect(url_for('admin_users'))

    if len(password) < 6:
        flash('密码长度不能少于6位', 'error')
        return redirect(url_for('admin_users'))

    db = get_db()
    existing = db.execute('SELECT id FROM users WHERE username = ?', (username,)).fetchone()
    if existing:
        flash(f'用户名 {username} 已存在', 'error')
        return redirect(url_for('admin_users'))

    db.execute('INSERT INTO users (username, password, is_admin, created_at) VALUES (?, ?, 0, ?)',
               (username, password, datetime.now().isoformat()))
    db.commit()
    flash(f'用户 {username} 创建成功', 'success')
    return redirect(url_for('admin_users'))


@app.route('/admin/users/delete', methods=['POST'])
@admin_required
def admin_delete_user():
    """删除用户"""
    user_id = request.form.get('user_id')

    db = get_db()
    user = db.execute('SELECT * FROM users WHERE id = ?', (user_id,)).fetchone()

    if not user:
        flash('用户不存在', 'error')
        return redirect(url_for('admin_users'))

    if user['is_admin']:
        flash('不能删除管理员账号', 'error')
        return redirect(url_for('admin_users'))

    db.execute('DELETE FROM users WHERE id = ?', (user_id,))
    db.commit()
    flash(f'用户 {user["username"]} 已删除', 'success')
    return redirect(url_for('admin_users'))


@app.route('/admin/users/reset-password', methods=['POST'])
@admin_required
def admin_reset_password():
    """重置用户密码"""
    user_id = request.form.get('user_id')
    new_password = request.form.get('new_password', '')

    if not new_password or len(new_password) < 6:
        flash('新密码长度不能少于6位', 'error')
        return redirect(url_for('admin_users'))

    db = get_db()
    user = db.execute('SELECT * FROM users WHERE id = ?', (user_id,)).fetchone()

    if not user:
        flash('用户不存在', 'error')
        return redirect(url_for('admin_users'))

    db.execute('UPDATE users SET password = ? WHERE id = ?',
               (new_password, user_id))
    db.commit()
    flash(f'用户 {user["username"]} 的密码已重置', 'success')
    return redirect(url_for('admin_users'))



if __name__ == '__main__':
    # 确保共享目录存在
    shared_dir = Path(CONFIG['SHARED_DIRECTORY'])
    if not shared_dir.exists():
        shared_dir.mkdir(parents=True)
        print(f"已创建共享目录: {shared_dir}")

    # 初始化数据库
    init_db()

    print("=" * 50)
    print("           文件共享服务器")
    print("=" * 50)
    print(f"共享目录: {shared_dir}")
    print(f"管理员: {CONFIG['ADMIN_USERNAME']}")
    print("\n访问地址:")
    print(f"  本机: http://127.0.0.1:{CONFIG['PORT']}")
    print(f"  局域网: http://<你的IP地址>:{CONFIG['PORT']}")
    print("\n按 Ctrl+C 停止服务器")
    print("=" * 50)
    print()

    app.run(host=CONFIG['HOST'], port=CONFIG['PORT'], debug=CONFIG['DEBUG'])
