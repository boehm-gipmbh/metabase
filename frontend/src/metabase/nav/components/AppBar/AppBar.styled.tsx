// eslint-disable-next-line no-restricted-imports
import styled from "@emotion/styled";

export const AppBarRoot = styled.header`
  position: relative;
  z-index: 4;
  background-color: var(--mb-color-background-blue);

  @media print {
    display: none;
  }
`;
