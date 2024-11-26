import CodeMirror, {
  type ReactCodeMirrorRef,
  type ViewUpdate,
} from "@uiw/react-codemirror";
import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useRef,
} from "react";

import { isEventOverElement } from "metabase/lib/dom";

import type { EditorProps, EditorRef } from "../Editor";

import S from "./CodeMirrorEditor.module.css";
import { useExtensions } from "./extensions";
import { convertIndexToPosition } from "./util";

type CodeMirrorEditorProps = EditorProps;

export const CodeMirrorEditor = forwardRef<EditorRef, CodeMirrorEditorProps>(
  function CodeMirrorEditor(props, ref) {
    const editor = useRef<ReactCodeMirrorRef>(null);
    const {
      query,
      onChange,
      readOnly,
      onSelectionChange,
      onRightClickSelection,
    } = props;
    const extensions = useExtensions({
      engine: query.engine() ?? undefined,
    });

    useImperativeHandle(ref, () => {
      return {
        focus() {
          editor.current?.editor?.focus();
        },
        resize() {
          // noop
        },
        getSelectionTarget() {
          return document.querySelector(".cm-selectionBackground");
        },
      };
    }, []);

    const handleUpdate = useCallback(
      (update: ViewUpdate) => {
        // handle selection changes
        const value = update.state.doc.toString();
        if (onSelectionChange) {
          onSelectionChange({
            start: convertIndexToPosition(
              value,
              update.state.selection.main.from,
            ),
            end: convertIndexToPosition(value, update.state.selection.main.to),
          });
        }
      },
      [onSelectionChange],
    );

    useEffect(() => {
      function handler(evt: MouseEvent) {
        const selection = editor.current?.state?.selection.main;
        if (!selection) {
          return;
        }

        const selections = Array.from(
          document.querySelectorAll(".cm-selectionBackground"),
        );

        if (selections.some(selection => isEventOverElement(evt, selection))) {
          evt.preventDefault();
          onRightClickSelection?.();
        }
      }
      document.addEventListener("contextmenu", handler);
      return () => document.removeEventListener("contextmenu", handler);
    }, [onRightClickSelection]);

    return (
      <CodeMirror
        ref={editor}
        className={S.editor}
        extensions={extensions}
        value={query.queryText()}
        readOnly={readOnly}
        onChange={onChange}
        height="100%"
        onUpdate={handleUpdate}
      />
    );
  },
);
