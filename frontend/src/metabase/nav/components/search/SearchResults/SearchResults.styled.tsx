import styled from "@emotion/styled";

export const EmptyStateContainer = styled.div`
  margin-top: 4rem;
  margin-bottom: 2rem;
`;

export const SearchResultsList = styled.ul`
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  padding: ${({ theme }) => theme.spacing.sm};
  gap: ${({ theme }) => theme.spacing.xs};
`;
