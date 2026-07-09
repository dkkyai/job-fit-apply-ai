#!/usr/bin/env python3
"""Convert a resume YAML file into a .tex file using resume_template.tex.jinja.

Usage:
    python3 yaml_to_tex.py RichardHatcherResume.yaml -o tailored_resume.tex
    python3 yaml_to_tex.py RichardHatcherResume.yaml -o tailored_resume.tex --pdf
"""
import argparse
import re
import shutil
import subprocess
from pathlib import Path

import yaml
from jinja2 import Environment, FileSystemLoader

SCRIPT_DIR = Path(__file__).resolve().parent

# Standard LaTeX special-character escapes. Order matters: backslash first,
# since later substitutions themselves introduce backslashes.
LATEX_ESCAPES = (
    (re.compile(r"\\"), r"\\textbackslash{}"),
    (re.compile(r"([{}_#%&$])"), r"\\\1"),
    (re.compile(r"~"), r"\\textasciitilde{}"),
    (re.compile(r"\^"), r"\\textasciicircum{}"),
)


def tex_escape(value):
    if value is None:
        return ""
    text = str(value).strip()
    for pattern, repl in LATEX_ESCAPES:
        text = pattern.sub(repl, text)
    return text


def format_month(value):
    """'2024-09' -> '9/2024'; bare '2024' -> '2024'; empty/None -> 'Present'."""
    if not value:
        return "Present"
    text = str(value).strip()
    if "-" not in text:
        return text
    year, month = text.split("-")
    return f"{int(month)}/{year}"


def build_bullets(raw_bullets):
    return [
        {"category": tex_escape(b["category"]), "text": tex_escape(b["text"])}
        for b in raw_bullets or []
    ]


def build_experience(raw_jobs):
    return [
        {
            "role": tex_escape(job["role"]),
            "company": tex_escape(job["company"]),
            "location": tex_escape(job.get("location", "")),
            "start_display": format_month(job.get("start")),
            "end_display": format_month(job.get("end")),
            "bullets": build_bullets(job.get("bullets")),
        }
        for job in raw_jobs or []
    ]


def build_projects(raw_projects):
    return [
        {
            "role": tex_escape(project.get("role", "")),
            "name": tex_escape(project.get("name", "")),
            "link": tex_escape(project.get("link", "")),
            "start_display": format_month(project.get("start")),
            "end_display": format_month(project.get("end")),
            "bullets": build_bullets(project.get("bullets")),
        }
        for project in raw_projects or []
    ]


def build_education(raw_education):
    return [
        {
            "degree": tex_escape(edu["degree"]),
            "field": tex_escape(edu.get("field", "")),
            "school": tex_escape(edu["school"]),
            "location": tex_escape(edu.get("location", "")),
            "year": tex_escape(edu.get("year", "")),
        }
        for edu in raw_education or []
    ]


def build_skills(raw_skills):
    groups = []
    for group in raw_skills or []:
        items = [tex_escape(item) for item in group.get("items", [])]
        groups.append({
            "label": tex_escape(group["label"]),
            "items_display": " · ".join(items),
        })
    return groups


def build_context(data):
    demographics = data["demographics"]
    return {
        "demographics": {
            "first_name": tex_escape(demographics["first_name"]),
            "last_name": tex_escape(demographics["last_name"]),
            "email": tex_escape(demographics["email"]),
            "phone": tex_escape(demographics["phone"]),
            "location": tex_escape(demographics["location"]),
            "postal_code": tex_escape(demographics.get("postal_code", "")),
        },
        "summary": tex_escape(data["summary"]),
        "experience": build_experience(data.get("experience")),
        "projects": build_projects(data.get("projects")),
        "education": build_education(data.get("education")),
        "skills": build_skills(data.get("skills")),
    }


def render_tex(context, template_dir=SCRIPT_DIR, template_name="resume_template.tex.jinja"):
    env = Environment(
        block_start_string=r"\BLOCK{",
        block_end_string="}",
        variable_start_string=r"\VAR{",
        variable_end_string="}",
        comment_start_string=r"\#{",
        comment_end_string="}",
        trim_blocks=True,
        lstrip_blocks=True,
        loader=FileSystemLoader(str(template_dir)),
        autoescape=False,
    )
    template = env.get_template(template_name)
    return template.render(**context)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("yaml_path", type=Path, help="Path to the resume YAML file")
    parser.add_argument(
        "-o", "--output", type=Path, default=SCRIPT_DIR / "tailored_resume.tex",
        help="Output .tex path (default: tailored_resume.tex next to this script)",
    )
    parser.add_argument("--pdf", action="store_true", help="Also compile the .tex to PDF with tectonic")
    args = parser.parse_args()

    data = yaml.safe_load(args.yaml_path.read_text())
    context = build_context(data)
    tex_source = render_tex(context)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(tex_source)
    print(f"Wrote {args.output}")

    if args.pdf:
        # tectonic needs fonts/ alongside the .tex file to resolve the
        # Path=fonts/ references baked into resume_template.tex.jinja.
        fonts_src = SCRIPT_DIR / "fonts"
        fonts_dst = args.output.parent / "fonts"
        if fonts_src.resolve() != fonts_dst.resolve() and not fonts_dst.exists():
            shutil.copytree(fonts_src, fonts_dst)
        subprocess.run(["tectonic", args.output.name], cwd=args.output.parent, check=True)
        print(f"Wrote {args.output.with_suffix('.pdf')}")


if __name__ == "__main__":
    main()
