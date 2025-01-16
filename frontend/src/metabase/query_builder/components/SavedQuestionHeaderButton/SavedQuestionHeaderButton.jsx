import PropTypes from "prop-types";
import { t } from "ttag";

import EditableText from "metabase/core/components/EditableText";
import { useTranslateContent } from "metabase/i18n/components/ContentTranslationContext";
import { Flex } from "metabase/ui";

import { CollectionIcon } from "./CollectionIcon";
import SavedQuestionHeaderButtonS from "./SavedQuestionHeaderButton.module.css";

SavedQuestionHeaderButton.propTypes = {
  className: PropTypes.string,
  question: PropTypes.object.isRequired,
  onSave: PropTypes.func,
};

function SavedQuestionHeaderButton({ question, onSave }) {
  const tc = useTranslateContent();
  return (
    <Flex align="center" gap="0.25rem">
      <EditableText
        className={SavedQuestionHeaderButtonS.HeaderTitle}
        isDisabled={!question.canWrite() || question.isArchived()}
        initialValue={tc(question._card, "name")}
        placeholder={t`Add title`}
        onChange={onSave}
        data-testid="saved-question-header-title"
      />

      <CollectionIcon
        collection={question?._card?.collection}
        question={question}
      />
    </Flex>
  );
}

export default SavedQuestionHeaderButton;
