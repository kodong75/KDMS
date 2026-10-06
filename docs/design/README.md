# KDMS 설계 문서

구성도·흐름도·아키텍처·일정 같은 그림 문서를 모은 곳. 목록은 [index.html](index.html)(브라우저로 연다).

![시스템 구성도](dist/d01-system-architecture.png)

## 형식

| 무엇 | 파일 | 용도 |
|---|---|---|
| 원본 | `tools/d??_*.py`(그림 정의·설명) → `d??-*.html`, `diagrams/d??-*.svg` | git 으로 변경 이력 관리. 브라우저로 바로 열린다 |
| 문서 배포본 | `dist/d??-*.pdf` | A4 가로. 1쪽 그림, 2쪽부터 설명 |
| 발표용 | `dist/d??-*.pptx` | 16:9 한 장. 블록·글·아이콘·연결선이 모두 PowerPoint 도형이라 편집할 수 있다(이미지 없음). 블록을 옮기면 연결선이 따라간다 |
| 미리보기 | `dist/d??-*.png` | 3200×1800 |

HTML·SVG 는 외부 접속 없이 열린다(폐쇄망). 글꼴은 `assets/fonts/`, 아이콘은 `assets/icons/` 에 있다.
PPTX 는 글꼴을 넣지 않으므로 발표 PC 에 `assets/fonts/otf/` 의 Pretendard(Regular·Bold)를 설치해야 모양이 같다(없으면 다른 글꼴로 바뀐다).

## 스타일

- 흰 배경, 플랫·벡터, 그림자 없음. 네이비(`#0B2545`, `#1B4F8F`)·스카이블루(`#3B8FD9`) + 흰색. 강조는 더 진한 테두리·채움.
- 블록은 모서리가 둥근 직사각형, 안에 아이콘(위)과 글(아래). 선은 블록 변에 붙는 연결선이라 블록을 옮기면 따라온다.
- 범례는 왼쪽 아래: 실선 = 실시간 질의, 점선 = 비동기 데이터 흐름.
- 글꼴 Pretendard, 아이콘 Material Symbols(Outlined). 라이선스는 [../licenses.md](../licenses.md) §6.
- 색 값은 `tools/diagram.py` 의 `C` 와 `assets/doc.css` 의 변수가 같아야 한다.

## 다시 만들기

```bash
python3 docs/design/tools/build.py    # HTML·SVG·index.html (표준 라이브러리만)
python3 docs/design/tools/export.py   # + PNG·PDF (Chromium 헤드리스), PPTX (python-pptx, 편집 가능한 도형)
```

- `export.py` 는 Chromium 을 찾아 쓴다. 다른 경로면 `CHROME=<경로>` 로 지정한다.
- 새 문서는 `tools/d01_system_architecture.py` 를 본떠 `build()`(그림)와 `EXPLAIN`(설명 HTML)을 만들고 `build.py` 의 `DOCS` 에 `module` 을 적는다.
