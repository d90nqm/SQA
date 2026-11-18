"""Create an isolated local Compose configuration from `docker compose config`."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
path = root / 'compose.local.json'
config = json.loads(path.read_text(encoding='utf-8-sig'))
config['name'] = 'dental-local'
for name in ['ngrok', 'chatbot-service', 'neo4j', 'mongodb', 'pgadmin']:
    config['services'].pop(name, None)
for name, service in config['services'].items():
    service.pop('container_name', None)
    service['restart'] = 'unless-stopped'
    for port in service.get('ports', []):
        port['host_ip'] = '127.0.0.1'
    env = service.setdefault('environment', {})
    if 'build' in service:
        service['build']['context'] = '.'
        env['JAVA_OPTS'] = '-Xms64m -Xmx256m -XX:ActiveProcessorCount=2'
        service['mem_limit'] = '512m'
        dockerfile = root / service['build']['dockerfile']
        local_dockerfile = dockerfile.with_name('Dockerfile.local')
        content = dockerfile.read_text(encoding='utf-8')
        content = content.replace('RUN gradle ', 'RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=locked gradle ')
        content = content.replace('--no-daemon', '--no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx384m')
        local_dockerfile.write_text(content, encoding='utf-8', newline='\n')
        service['build']['dockerfile'] = str(local_dockerfile.relative_to(root)).replace('\\', '/')
    for mount in service.get('volumes', []):
        if mount['type'] == 'bind':
            source = Path(mount['source'])
            if source.is_absolute() and source.is_relative_to(root):
                mount['source'] = './' + source.relative_to(root).as_posix()
    if name == 'axon-server':
        env['JAVA_TOOL_OPTIONS'] = '-Xms128m -Xmx384m -XX:ActiveProcessorCount=2'
        service['mem_limit'] = '768m'
config['services']['registry-service']['healthcheck']['test'] = ['CMD-SHELL', 'wget -qO- http://localhost:8761/ > /dev/null || exit 1']
config['services']['postgres']['ports'][0]['published'] = '15432'
mounts = config['services']['postgres']['volumes']
if not any(m['target'].endswith('02-local-admin.sql') for m in mounts):
    mounts.append({'type': 'bind', 'source': './scripts/local-admin.sql', 'target': '/docker-entrypoint-initdb.d/02-local-admin.sql', 'read_only': True})
config['services']['payment-service']['environment'].update({
    'PAYOS_CLIENT_ID': 'local-disabled',
    'PAYOS_API_KEY': 'local-disabled',
    'PAYOS_CHECKSUM_KEY': '0' * 64,
})
config['services']['notification-service']['environment'].update({
    'SPRING_MAIL_HOST': '127.0.0.1',
    'SPRING_MAIL_PORT': '2525',
    'SPRING_MAIL_USERNAME': 'local-disabled',
    'SPRING_MAIL_PASSWORD': 'local-disabled',
    'SPRING_MAIL_PROPERTIES_MAIL_SMTP_CONNECTIONTIMEOUT': '2000',
    'SPRING_MAIL_PROPERTIES_MAIL_SMTP_TIMEOUT': '2000',
})
for section in ['networks', 'volumes']:
    for name, entry in config.get(section, {}).items():
        if 'name' in entry:
            entry['name'] = f'dental-local_{name}'
path.write_text(json.dumps(config, indent=2), encoding='utf-8')
# Windows Git may check shell files out with CRLF; PostgreSQL executes this on Linux.
init = root / 'scripts/init-all-databases.sh'
init.write_text(init.read_text(encoding='utf-8'), encoding='utf-8', newline='\n')
print('Prepared local Compose: management services only; external integrations disabled.')
