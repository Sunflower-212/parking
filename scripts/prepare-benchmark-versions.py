"""Export the two immutable Git snapshots; optionally build their four historical images."""
import argparse,subprocess,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
VERSIONS={'original':('797313c','parking-baseline'),'released':('21d3012','parking-released')}
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--build',action='store_true');args=parser.parse_args()
    base=ROOT/'.work/benchmark-sources';base.mkdir(parents=True,exist_ok=True)
    for name,(ref,image) in VERSIONS.items():
        sha=subprocess.check_output(['git','rev-parse',ref],cwd=ROOT).decode().strip()
        source=base/name;marker=source/'.benchmark-source-commit'
        if source.exists():
            if not marker.exists() or marker.read_text().strip()!=sha:raise RuntimeError('Existing snapshot does not match expected commit: '+name)
        else:
            archive=base/(name+'.zip')
            subprocess.run(['git','archive','--format=zip','--output',str(archive),sha],cwd=ROOT,check=True)
            with zipfile.ZipFile(archive) as entries:
                if any(not (source/item).resolve().is_relative_to(source.resolve()) for item in entries.namelist()):raise RuntimeError('Invalid archive path')
                source.mkdir();entries.extractall(source)
            marker.write_text(sha+'\n')
        if args.build:
            for service in ('backend','frontend'):
                subprocess.run(['docker','build','--label','org.opencontainers.image.revision='+sha,'-f',str(source/f'deploy/{service}.Dockerfile'),'-t',f'{image}-{service}:{ref}',str(source)],cwd=ROOT,check=True)
        print(name+' source verified at '+sha)
